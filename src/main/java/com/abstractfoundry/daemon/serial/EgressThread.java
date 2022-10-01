/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import com.abstractfoundry.daemon.common.COBS;
import com.abstractfoundry.daemon.common.CRC16;
import com.abstractfoundry.daemon.common.Pause;
import com.abstractfoundry.daemon.common.ThreadSafe;
import com.lmax.disruptor.dsl.Disruptor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Do not hold-off when cursor is out of bounds due to ACKed slots at start of window.
// TODO: Prevent so many TimeoutException objects being allocated by the Exchanger.
// TODO: Call handleFeedback() in between every sub-operation?

class EgressThread extends Thread {

	private static final Logger logger = LoggerFactory.getLogger(EgressThread.class);

	private final static int MAX_PUBLICATION_BATCH_SIZE = 32; // Maximum disruptor publication batch size.
	private static final int WINDOW_SIZE = 16;
	private static final int FLUSH_COUNT = 256;
	private static final double UTILISATION = 1.0;
	private static final long ALLOWABLE_BANDWIDTH = (long) (UTILISATION * (3_000_000 / 10)); // Use fraction of 3 MBit/s UART (8 bit character, 1 start bit, 1 stop bit).
	private static final long ACTIVE_PERIOD_NANOSECONDS = 2_500_000L; // Spin every 2.5 milliseconds when window is not empty.
	private static final long IDLE_PERIOD_NANOSECONDS = 1_000_000_000L; // Spin every 1 second when window is empty.
	private static final long CURSOR_RESET_DELAY_NANOSECONDS = 12_500_000L;
	private static final int MAX_BATCH_BYTES = (int) (ALLOWABLE_BANDWIDTH * ACTIVE_PERIOD_NANOSECONDS / 1000000000L); // Maximum number of bytes per batch (distributed over up to MAX_PUBLICATION_BATCH_SIZE slots).
	private static final int CURSOR_RESET_HOLDOFF = (int) (CURSOR_RESET_DELAY_NANOSECONDS / ACTIVE_PERIOD_NANOSECONDS);

	private static class Slot {

		final byte[] bytes = new byte[256];
		int length = 0;
		int attempts = 0;

		void reset() {
			length = 0;
			attempts = 0;
		}

	}

	private final TransferBatch pendingBatch;
	private final PublicationBatch<EncodedFrame> outboxBatch;
	private final byte[] scratchpad = new byte[256];
	private final Slot[] slots = new Slot[256];
	private boolean initialised = false;
	private int head = 0, tail = 0, cursor = 0, holdoff = 0, countdown = FLUSH_COUNT;
	private final AtomicInteger feedbackAcknowledgementNumber = new AtomicInteger(-1);
	private final AtomicInteger feedbackInitialisationCount = new AtomicInteger(0);
	private final AtomicInteger feedbackUninitialisationCount = new AtomicInteger(0);
	private final AtomicInteger feedbackPongCount = new AtomicInteger(0);

	EgressThread(TransferBatch pendingBatch, Disruptor<EncodedFrame> outbox) {
		super("Foundry Egress");
		if (pendingBatch.capacity() > WINDOW_SIZE / 2) {
			throw new IllegalArgumentException("Batch capacity over half the window size.");
		}
		this.pendingBatch = pendingBatch;
		this.outboxBatch = new PublicationBatch<>(MAX_PUBLICATION_BATCH_SIZE, 256, outbox, EgressThread::transfer);
		for (var index = 0; index < this.slots.length; index++) {
			this.slots[index] = new Slot();
		}
		if (MAX_BATCH_BYTES < 256) {
			logger.warn("Bandwidth restrictions limit maximum frame size to {} bytes.", MAX_BATCH_BYTES);
		}
	}

	@Override
	public void run() {
		while (!Thread.interrupted()) {
			try {
				var timestamp = System.nanoTime();
				// TODO: Call handleFeedback() here too?
				acceptUntilNextTransmission(timestamp);
				handleFeedback();
				publishBatch(MAX_BATCH_BYTES);
			} catch (InterruptedException exception) {
				logger.error("Thread interrupted, terminating.", exception);
				Thread.currentThread().interrupt();
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception.", exception);
				Pause.onError();
			}
		}
	}

	private void accept(int index) {
		var sequence = tail; // Allocated sequence number.
		var slot = slots[sequence];
		slot.reset();
		slot.bytes[0] = 0x00; // Placeholder for COBS overhead byte.
		var length = 1 + pendingBatch.read(index, slot.bytes, 1); // Length of prefix (COBS byte and payload).
		if (length <= 0) {
			throw new IllegalStateException("Buffer is empty.");
		} else if (length > 252) {
			throw new IllegalStateException("Buffer is too full.");
		}
		slot.bytes[length] = (byte) sequence;
		var checksum = CRC16.calculate(slot.bytes, 1, length); // CRC16 checksum.
		slot.bytes[length + 1] = (byte) ((checksum >> 8) & 0xFF);
		slot.bytes[length + 2] = (byte) (checksum & 0xFF);
		COBS.encode(slot.bytes, 0, 1, length + 2); // COBS encoding.
		slot.bytes[length + 3] = 0x00; // Delimiter.
		slot.length = length + 4; // 1 byte sequence number, 2 byte CRC and 1 byte delimiter.
		tail = mod(tail + 1);
	}

	private void acceptUntilNextTransmission(long timestamp) throws InterruptedException {
		long duration;
		while ((duration = nanosecondsUntilNextTransmission(timestamp)) > 0) {
			var space = WINDOW_SIZE - mod(tail - head);
			if (space >= pendingBatch.capacity() && duration > 0.95 * ACTIVE_PERIOD_NANOSECONDS) { // Do not bother entering the exchanger, and potentially allocating a TimeoutException, if we are not at the start of the cycle (5% of the active period should be enough for us to drain the collector into our window).
				try {
					pendingBatch.accept(duration, TimeUnit.NANOSECONDS);
				} catch (TimeoutException exception) {
					break;
				}
				var count = pendingBatch.count();
				for (var index = 0; index < count; index++) {
					accept(index);
				}
			} else {
				LockSupport.parkNanos(duration);
			}
		}
	}

	private long nanosecondsUntilNextTransmission(long timestamp) {
		if (tail != head || !initialised) {
			return Math.max(timestamp + ACTIVE_PERIOD_NANOSECONDS - System.nanoTime(), 0);
		} else {
			return Math.max(timestamp + IDLE_PERIOD_NANOSECONDS - System.nanoTime(), 0);
		}
	}

	private void handleFeedback() {
		var pongs = feedbackPongCount.getAndSet(0);
		if (countdown > 0 && pongs > 0) {
			countdown -= pongs;
		}
		var acknowledged = feedbackAcknowledgementNumber.getAndSet(-1);
		if (acknowledged >= 0 && mod(acknowledged - head) < mod(tail - head)) {
			head = mod(acknowledged + 1);
		}
		var initialisations = feedbackInitialisationCount.getAndSet(0);
		if (!initialised && initialisations > 0) {
			logger.info("Serial protocol egress channel initialised.");
			initialised = true;
		}
		var uninitialisations = feedbackUninitialisationCount.getAndSet(0);
		if (initialised && uninitialisations > 0) {
			logger.info("Serial protocol egress channel uninitialised.");
			countdown = FLUSH_COUNT;
			initialised = false;
		}
	}

	private void publishBatch(int limit) {
		try {
			int accumulator = 0;
			if (initialised) {
				if (head != tail) {
					while (true) {
						if (cursor == tail && holdoff++ < CURSOR_RESET_HOLDOFF) { // If the cursor has hit the end of the window then wait some number of cycles before (potentially) wrapping (since new data might be queued in meantime).
							break;
						} else if (mod(cursor - head) >= mod(tail - head)) { // In all other situations where the cursor lies outside the window (e.g. slot at cursor position was just acknowleged) reset it to the head of the buffer.
							cursor = head;
						}
						holdoff = 0;
						var slot = slots[cursor];
						if (slot.length > MAX_BATCH_BYTES) {
							throw new RuntimeException("Bandwidth restrictions prevent egress thread for making progress."); // The batch size limit is such that we will never be able to send this frame.
						}
						accumulator += slot.length;
						if (accumulator > limit) {
							break; // Publication would breach limit, break and flush the batch.
						}
						outboxBatch.insert(slot.bytes, 0, slot.length);
						var attempts = ++slot.attempts;
						if (attempts > 2) {
							logger.warn("Excessive serial retransmission (attempts = {}, slot = {}).", attempts, cursor); // TODO: Better to collect statistics.
						} else if (attempts > 1) {
							// TODO: Collect statistics.
						}
						cursor = mod(cursor + 1);
					}
				}
			} else if (!initialised && countdown > 0) { // TODO: Break out into method.
				var length = writePing(scratchpad);
				accumulator += length;
				if (accumulator <= limit) {
					outboxBatch.insert(scratchpad, 0, length);
				}
			} else if (!initialised && countdown <= 0) { // TODO: Break out into method.
				var length = writeInitialise(scratchpad, 1, head);
				accumulator += length;
				if (accumulator <= limit) {
					outboxBatch.insert(scratchpad, 0, length);
				}
			}
		} finally {
			outboxBatch.flush();
		}
	}

	/**
	 * Thread-safe method used by the ingress thread to provide feedback to the egress thread.
	 *
	 * @param buffer Ephemeral data buffer containing the feedback frame.
	 * @param offset The offset into the buffer.
	 * @param length The length of the frame in the buffer.
	 */
	@ThreadSafe
	void feedback(byte[] buffer, int offset, int length) {
		if (length < 2) {
			throw new RuntimeException("Truncated feedback.");
		}
		var code = buffer[1] & 0xFF; // Command code.
		switch (code) {
			case 0xAA: // ACKNOWLEDGE.
				if (length < 3) {
					throw new RuntimeException("Acknowledgement returned without a sequence number.");
				} else {
					feedbackAcknowledgementNumber.set(buffer[2] & 0xFF);
				}	break;
			case 0xB4: // INITIALISED.
				feedbackInitialisationCount.incrementAndGet();
				break;
			case 0xCC: // UNINITIALISED.
				feedbackUninitialisationCount.incrementAndGet();
				break;
			case 0xFF: // PONG.
				if (length < 3 || (buffer[2] & 0xFF) < 1) {
					throw new RuntimeException("Counterparty does not support minimal protocol version (>= 1).");
				} else {
					feedbackPongCount.incrementAndGet();
				}	break;
			default:
				throw new UnsupportedOperationException("Unsupported feedback code = " + code + ".");
		}
	}

	private int writePing(byte[] array) {
		var length = 5;
		array[1] = 0x00; // PING.
		var checksum = CRC16.calculate(array, 1, 1);
		array[2] = (byte) ((checksum >> 8) & 0xFF);
		array[3] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 3);
		array[4] = 0x00; // DELIMITER.
		return length;
	}

	private int writeInitialise(byte[] array, int version, int sequence) {
		var length = 7;
		array[1] = 0x1E; // INITIALISE.
		array[2] = (byte) version;
		array[3] = (byte) sequence;
		var checksum = CRC16.calculate(array, 1, 3);
		array[4] = (byte) ((checksum >> 8) & 0xFF);
		array[5] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 5);
		array[6] = 0x00; // DELIMITER.
		return length;
	}

	private static void transfer(byte[] bytes, int length, Frame frame) {
		frame.write(bytes, 0, length);
	}

	private static int mod(int value) {
		return Math.floorMod(value, 256);
	}

}
