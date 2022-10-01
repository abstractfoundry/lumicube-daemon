/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import com.abstractfoundry.daemon.common.COBS;
import com.abstractfoundry.daemon.common.CRC16;
import com.abstractfoundry.daemon.common.Pause;
import com.lmax.disruptor.dsl.Disruptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Only sleep after read if it's been a short time since the last cycle.

class IngressThread extends Thread {

	private static final Logger logger = LoggerFactory.getLogger(IngressThread.class);

	private final static int MAX_BACKLOG = 16384; // Maximum ingress latency in bytes.
	private final static int MAX_PUBLICATION_BATCH_SIZE = 32;
	private static final long ACCUMULATION_PERIOD_NANOSECONDS = 2_500_000L; // Period for stream to accumulate data (2.5 milliseconds).

	private final InputStream inputStream;
	private final EgressThread egressThread;
	private final byte[] backlog = new byte[MAX_BACKLOG];
	private final byte[] buffer = new byte[256]; // Buffer in which to assemble frames.
	private final byte[] scratchpad = new byte[256]; // Scratchpad buffer.
	private final PublicationBatch<DecodedFrame> inboxBatch; // Batch of frames to publish to the inbox.
	private final PublicationBatch<EncodedFrame> outboxBatch; // Batch of frames to publish to the outbox.
	private boolean initialised = false;
	private int pointer = 0, accept = 0;
	private boolean respondPong = false, respondInitialised = false, respondUninitialised = false;
	private int respondAcknowledge = -1;

	IngressThread(InputStream inputStream, EgressThread egressThread, Disruptor<DecodedFrame> inbox, Disruptor<EncodedFrame> outbox) {
		super("Foundry Ingress");
		this.inputStream = inputStream;
		this.egressThread = egressThread;
		this.inboxBatch = new PublicationBatch<>(MAX_PUBLICATION_BATCH_SIZE, 256, inbox, IngressThread::transfer);
		this.outboxBatch = new PublicationBatch<>(MAX_PUBLICATION_BATCH_SIZE, 256, outbox, IngressThread::transfer);
	}

	@Override
	public void run() {
		while (!Thread.interrupted()) {
			try {
				var value = inputStream.read(); // Wait until at least one byte is available.
				LockSupport.parkNanos(ACCUMULATION_PERIOD_NANOSECONDS); // Allow the kernel to accumulate 2.5 millisecond's worth of data (this strategy avoids the thread waking up on every frame).
				var skipped = false;
				var available = inputStream.available();
				while (available > MAX_BACKLOG) {
					inputStream.read(backlog, 0, MAX_BACKLOG); // Read into backlog, but discarded immediately.
					logger.warn("Skipped over {} bytes to reduce ingress latency.", MAX_BACKLOG);
					skipped = true;
					available = inputStream.available();
				}
				if (!skipped) {
					consume(value); // This first byte is discarded if we skipped a subsequent chunk.
				}
				var read = inputStream.read(backlog, 0, available); // Bulk read into backlog (this should not block).
				for (var offset = 0; offset < read; offset++) { // Fully consume backlog.
					consume(backlog[offset] & 0xFF);
				}
				respond();
				outboxBatch.flush();
				inboxBatch.flush();
			} catch (InterruptedException exception) {
				logger.error("Thread interrupted, terminating.", exception);
				Thread.currentThread().interrupt();
			} catch (IOException exception) {
				logger.error("I/O error.", exception);
				Pause.onError();
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception.", exception);
				Pause.onError();
			}
		}
	}

	private void consume(int value) throws InterruptedException {
		if (value != 0x00) {
			if (pointer >= 255) {
				logger.warn("Oversize frame or corrupted delimiter."); // TODO: Better to collect statistics.
				pointer = 0; // Oversize frame or corrupted delimiter, continue writing to start of buffer.
			}
			buffer[pointer++] = (byte) value;
		} else {
			process();
		}
	}

	private void process() throws InterruptedException {
		try {
			var length = pointer;
			if (length == 0) { // Empty frame.
				return;
			} else if (length < 4) { // A valid frame must contain at least one COBS byte, one command byte, and two CRC bytes.
				logger.warn("Truncated frame: length = {}.", length); // TODO: Better to collect statistics.
				return;
			}
			var counter = COBS.decode(buffer, 0, 0, length);
			if (counter != 0) {
				logger.warn("COBS error: length = {}, counter = {}.", length, counter); // TODO: Better to collect statistics.
				return;
			}
			var checksum = CRC16.calculate(buffer, 1, length - 1);
			if (checksum != 0) {
				logger.warn("CRC error: length = {}, checksum = {}.", length, checksum); // TODO: Better to collect statistics.
				return;
			}
			var code = buffer[1] & 0xFF; // Command code.
			if (code >= 0xA0) { // Response code range.
				egressThread.feedback(buffer, 0, length); // TODO: Collect this information and only atomically update egress thread once per batch.
			} else if (code == 0x00) { // PING.
				respondPong = true;
			} else if (code == 0x1E) { // INITIALISE.
				handleInitialise();
			} else if (!initialised) { // All other commands may not be handled until we are initialised.
				respondUninitialised = true;
			} else if (code == 0x2D) { // MESSAGE.
				handleMessage();
			} else {
				logger.error("Unsupported command code: {}.", code);
			}
		} finally {
			pointer = 0;
		}
	}

	private void handleInitialise() throws InterruptedException {
		if (pointer == 6 || (buffer[2] & 0xFF) == 1) { // Check the frame format is correct and that the version is equal to 1.
			accept = buffer[3] & 0xFF; // Note: We always update the next accepted sequence number, whether we are already initialised or not.
			respondInitialised = true;
			if (!initialised) {
				initialised = true;
				logger.info("Serial ingress channel initialised.");
			}
		} else {
			if (!initialised) {
				respondUninitialised = true;
			}
			throw new RuntimeException("Counterparty attempted to initialise channel with an unsupported format or version.");
		}
	}

	private void handleMessage() {
		var sequence = buffer[pointer - 3] & 0xFF; // Sequence number is always the last byte before the CRC bytes.
		if (sequence == accept) {
			accept = mod(accept + 1);
			inboxBatch.insert(buffer, 0, pointer);
			respondAcknowledge = sequence;
		} else {
			respondAcknowledge = mod(accept - 1);
			logger.debug("Ignoring incoming frame: sequence = {}, accept = {}.", sequence, accept); // TODO: Better to collect statistics.
		}
	}

	private void respond() {
		if (respondPong) {
			try {
				var length = writePong(scratchpad, 1);
				outboxBatch.insert(scratchpad, 0, length);
			} finally {
				respondPong = false;
			}
		}
		if (respondUninitialised) {
			try {
				var length = writeUninitialised(scratchpad);
				outboxBatch.insert(scratchpad, 0, length);
			} finally {
				respondUninitialised = false;
			}
		}
		if (respondInitialised) {
			try {
				var length = writeInitialised(scratchpad);
				outboxBatch.insert(scratchpad, 0, length);
			} finally {
				respondInitialised = false;
			}
		}
		if (respondAcknowledge >= 0) {
			try {
				var sequence = respondAcknowledge;
				var length = writeAcknowledge(scratchpad, sequence);
				outboxBatch.insert(scratchpad, 0, length);
			} finally {
				respondAcknowledge = -1;
			}
		}
	}

	private static void transfer(byte[] bytes, int length, Frame frame) {
		frame.write(bytes, 0, length);
	}

	private static int writeAcknowledge(byte[] array, int sequence) {
		var length = 6;
		array[1] = (byte) 0xAA; // PONG.
		array[2] = (byte) sequence;
		var checksum = CRC16.calculate(array, 1, 2);
		array[3] = (byte) ((checksum >> 8) & 0xFF);
		array[4] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 4);
		array[5] = 0x00; // DELIMITER.
		return length;
	}

	private static int writeInitialised(byte[] array) {
		var length = 5;
		array[1] = (byte) 0xB4; // INITIALISED.
		var checksum = CRC16.calculate(array, 1, 1);
		array[2] = (byte) ((checksum >> 8) & 0xFF);
		array[3] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 3);
		array[4] = 0x00; // DELIMITER.
		return length;
	}

	private static int writeUninitialised(byte[] array) {
		var length = 5;
		array[1] = (byte) 0xCC; // UNINITIALISED.
		var checksum = CRC16.calculate(array, 1, 1);
		array[2] = (byte) ((checksum >> 8) & 0xFF);
		array[3] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 3);
		array[4] = 0x00; // DELIMITER.
		return length;
	}

	private static int writePong(byte[] array, int version) {
		var length = 6;
		array[1] = (byte) 0xFF; // PONG.
		array[2] = (byte) version;
		var checksum = CRC16.calculate(array, 1, 2);
		array[3] = (byte) ((checksum >> 8) & 0xFF);
		array[4] = (byte) (checksum & 0xFF);
		COBS.encode(array, 0, 1, 4);
		array[5] = 0x00; // DELIMITER.
		return length;
	}

	private static int mod(int value) {
		return Math.floorMod(value, 256);
	}

}
