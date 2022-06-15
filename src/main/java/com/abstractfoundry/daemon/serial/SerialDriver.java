/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import com.abstractfoundry.daemon.common.FluentThreadFactory;
import com.abstractfoundry.daemon.common.Pause;
import com.abstractfoundry.daemon.common.ThreadSafe;
import com.fazecast.jSerialComm.SerialPort;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.dsl.Disruptor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerialDriver {

	private static final Logger logger = LoggerFactory.getLogger(SerialDriver.class);

	private static final int INBOX_SIZE = 256;
	private static final int OUTBOX_SIZE = 256;
	private static final int COLLECTOR_SIZE = 256;
	private static final int MAX_EXCHANGE_BATCH_SIZE = 8;

	private final Consumer<DecodedFrame> callback;
	private final SerialPort serialPort;
	private final InputStream serialInputStream;
	private final OutputStream serialOutputStream;
	private final TransferBatch collectorBatch, egressBatch;
	private final Disruptor<DecodedFrame> inbox;
	private final Disruptor<EncodedFrame> outbox;
	private final Disruptor<PartialFrame> collector;
	private final EgressThread egressThread;
	private final IngressThread ingressThread;

	public SerialDriver(Consumer<DecodedFrame> callback, String devicePath) {
		this.callback = callback;

		SerialPort port;
		if (devicePath != null && !devicePath.isBlank()) {
			port = SerialPort.getCommPort(devicePath);
		} else {
			SerialPort[] ports = SerialPort.getCommPorts();
			if (ports.length < 1) {
				throw new RuntimeException("Failed to automatically discover serial port.");
			}
			port = ports[0];
		}
		
		this.serialPort = port;
		serialPort.setComPortParameters(3000000, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
		serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0); // Block indefinitely on read until at least one byte has arrived.
		var bufferInputStream = false; // The ingress thread carefully buffers the input stream internally, so this additional buffer is probably not helpful.
		var bufferOutputStream = true;
		var inputStream = serialPort.getInputStream();
		var outputStream = serialPort.getOutputStream();
		this.serialInputStream = bufferInputStream ? new BufferedInputStream(inputStream) : inputStream;
		this.serialOutputStream = bufferOutputStream ? new BufferedOutputStream(outputStream) : outputStream;

		var pair = TransferBatch.createPair(MAX_EXCHANGE_BATCH_SIZE, 256);
		this.collectorBatch = pair.getHead();
		this.egressBatch = pair.getTail();

		this.inbox = new Disruptor<>(DecodedFrame::new, INBOX_SIZE,
			new FluentThreadFactory()
				.setName("Foundry Inbox Disruptor")
				.setDaemon(true)
		);
		inbox.handleEventsWith(this::handleInbox);
		this.outbox = new Disruptor<>(EncodedFrame::new, OUTBOX_SIZE,
			new FluentThreadFactory()
				.setName("Foundry Outbox Disruptor")
				.setDaemon(true)
		);
		outbox.handleEventsWith(this::handleOutbox);
		this.collector = new Disruptor<>(PartialFrame::new, COLLECTOR_SIZE,
			new FluentThreadFactory()
				.setName("Foundry Collector Disruptor")
				.setDaemon(true)
		);
		collector.handleEventsWith(this::handleCollector);

		this.egressThread = new EgressThread(egressBatch, outbox);
		egressThread.setDaemon(true);
		this.ingressThread = new IngressThread(serialInputStream, egressThread, inbox, outbox);
		ingressThread.setDaemon(true);
	}

	public void start() {
		serialPort.openPort();
		inbox.start();
		outbox.start();
		collector.start();
		egressThread.start();
		ingressThread.start();
	}

	/**
	 * Thread-safe method used by client threads to submit several frames for (reliable) transmission.
	 * <p>
	 * The data is supplied by a one argument callback. At most 251 bytes may be
	 * sent in a single frame.
	 */
	@ThreadSafe
	public <A> void batch(EventTranslatorOneArg<PartialFrame, A> writer, A[] arguments) {
		collector.publishEvents(writer, arguments);
	}
	
	/**
	 * Thread-safe method used by client threads to submit a frame for (reliable) transmission.
	 * <p>
	 * The data at the given offset into the buffer is copied, so it is safe to
	 * modify the buffer after this method returns. At most 251 bytes may be sent
	 * in a single frame.
	 *
	 * @param buffer The buffer containing the data to be sent.
	 * @param offset The offset into the buffer.
	 * @param length The length of data in the buffer.
	 */
	@ThreadSafe
	public void submit(byte[] buffer, int offset, int length) {
		if (buffer == null) {
			throw new IllegalArgumentException("Invalid buffer.");
		} else if (offset < 0 || length < 0 || offset + length > buffer.length) {
			throw new IllegalArgumentException("Invalid offset or length.");
		}
		collector.publishEvent(SerialDriver::transfer, buffer, offset, length);
	}

	/**
	 * Thread-safe method used by client threads to submit a frame for (reliable) transmission.
	 * <p>
	 * The data is supplied by a one argument callback. At most 251 bytes may be
	 * sent in a single frame.
	 */
	@ThreadSafe
	public <A> void submit(EventTranslatorOneArg<PartialFrame, A> writer, A argument0) {
		collector.publishEvent(writer, argument0);
	}


	/**
	 * Thread-safe method used by client threads to submit a frame for (reliable) transmission.
	 * <p>
	 * The data is supplied by a two argument callback. At most 251 bytes may be
	 * sent in a single frame.
	 */
	@ThreadSafe
	public <A, B> void submit(EventTranslatorTwoArg<PartialFrame, A, B> writer, A argument0, B argument1) {
		collector.publishEvent(writer, argument0, argument1);
	}


	/**
	 * Thread-safe method used by client threads to submit a frame for (reliable) transmission.
	 * <p>
	 * The data is supplied by a three argument callback. At most 251 bytes may be
	 * sent in a single frame.
	 */
	@ThreadSafe
	public <A, B, C> void submit(EventTranslatorThreeArg<PartialFrame, A, B, C> writer, A argument0, B argument1, C argument2) {
		collector.publishEvent(writer, argument0, argument1, argument2);
	}
	
	private void handleInbox(DecodedFrame frame, long sequence, boolean endOfBatch) {
		try {
			callback.accept(frame);
		} catch (RuntimeException exception) {
			logger.error("Unhandled exception processing inbox.", exception);
		} finally {
			frame.reset(); // Reset the frame so it can be repopulated.
		}
	}

	private void handleOutbox(EncodedFrame frame, long sequence, boolean endOfBatch) {
		try {
			serialOutputStream.write(frame.getBytes(), 0, frame.getLength());
			if (endOfBatch) {
				serialOutputStream.flush();
			}
		} catch (IOException exception) {
			logger.warn("I/O error whilst transmitting frame.", exception);
			Pause.onError();
		} catch (RuntimeException exception) {
			logger.error("Unhandled exception processing outbox.", exception);
		} finally {
			frame.reset(); // Reset the frame so it can be repopulated.
		}
	}

	private void handleCollector(PartialFrame frame, long sequence, boolean endOfBatch) {
		try {
			collectorBatch.insert(frame.getBytes(), 0, frame.getLength());
			if (endOfBatch) {
				collectorBatch.flush();
			}
		} catch (RuntimeException exception) {
			logger.error("Unhandled exception processing collector.", exception);
		} finally {
			frame.reset(); // Reset the frame so it can be repopulated.
		}
	}

	public int inboxBacklog() {
		return backlog(inbox);
	}

	public int outboxBacklog() {
		return backlog(outbox);
	}

	public int collectorBacklog() {
		return backlog(collector);
	}

	private static int backlog(Disruptor disruptor) {
		return (int) (
			disruptor.getRingBuffer().getBufferSize() - disruptor.getRingBuffer().remainingCapacity()
		);
	}

	private static void transfer(PartialFrame frame, long sequence, byte[] buffer, int offset, int length) {
		frame.write(buffer, offset, length);
	}

}
