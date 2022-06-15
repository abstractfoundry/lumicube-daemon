/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.ThreadSafe;
import com.abstractfoundry.daemon.serial.DecodedFrame;
import com.abstractfoundry.daemon.serial.PartialFrame;
import com.abstractfoundry.daemon.serial.SerialDriver;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerialConnector {

	private static final Logger logger = LoggerFactory.getLogger(SerialConnector.class);
	
	private final SerialDriver serialDriver;
	private final BroadcastHandler broadcastHandler;
	private final ServiceHandler requestHandler;
	private final ServiceHandler responseHandler;

	public SerialConnector(String devicePath, BroadcastHandler broadcastHandler, ServiceHandler requestHandler, ServiceHandler responseHandler) {
		this.serialDriver = new SerialDriver(this::receiveFrame, devicePath);
		this.broadcastHandler = broadcastHandler;
		this.requestHandler = requestHandler;
		this.responseHandler = responseHandler;
	}

	public void start() {
		serialDriver.start();
	}

	/**
	 * Thread-safe method used by client threads to send a broadcast.
	 */
	@ThreadSafe
	public void sendBroadcast(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		checkPackable(offset, length);
		var messageId = (sourceId & 0x7F) | (sourceId != 0 ? (typeId & 0xFFFF) << 8 : (typeId & 0x3) << 8) | (priority & 0x1F) << 24;
		var parameterPack = (offset & 0xFFFF) | (length & 0xFF) << 16 | (transferId & 0x1F) << 24; // Pack into long integer, rather than allocated payload object.
		serialDriver.submit(SerialConnector::writer, buffer, messageId, parameterPack);
	}

	/**
	 * Thread-safe method used by client threads to send a request.
	 */
	@ThreadSafe
	public void sendRequest(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		checkPackable(offset, length);
		var messageId = 0x8080 | (sourceId & 0x7F) | (destinationId & 0x7F) << 8 | (typeId & 0xFF) << 16 | (priority & 0x1F) << 24;
		var parameterPack = (offset & 0xFFFF) | (length & 0xFF) << 16 | (transferId & 0x1F) << 24; // Pack into long integer, rather than allocated payload object.
		serialDriver.submit(SerialConnector::writer, buffer, messageId, parameterPack);
	}

	/**
	 * Thread-safe method used by client threads to send several requests.
	 * This generates some garbage to be collected, but it should be offset by
	 * the benefit of not waking up the collector thread several times.
	 */
	@ThreadSafe
	public void sendRequests(int sourceId, int destinationId, int typeId, int[] transferIds, int priority, ByteBuffer[] buffers, int count) {
		var messageId = 0x8080 | (sourceId & 0x7F) | (destinationId & 0x7F) << 8 | (typeId & 0xFF) << 16 | (priority & 0x1F) << 24;
		var payloads = new Payload[count];
		for (var index = 0; index < count; index++) {
			var buffer = buffers[index];
			var offset = buffer.position();
			var length = buffer.limit() - offset;
			payloads[index] = new Payload(messageId, transferIds[index], buffer.array(), offset, length);
			buffer.position(length); // Indicate buffer has been read.
		}
		serialDriver.batch(SerialConnector::writer, payloads);
	}

	/**
	 * Thread-safe method used by client threads to send a response.
	 */
	@ThreadSafe
	public void sendResponse(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		checkPackable(offset, length);
		var messageId = 0x80 | (sourceId & 0x7F) | (destinationId & 0x7F) << 8 | (typeId & 0xFF) << 16 | (priority & 0x1F) << 24;
		var parameterPack = (offset & 0xFFFF) | (length & 0xFF) << 16 | (transferId & 0x1F) << 24; // Pack into long integer, rather than allocated payload object.
		serialDriver.submit(SerialConnector::writer, buffer, messageId, parameterPack);
	}

	private void receiveFrame(DecodedFrame frame) {
		var length = frame.getLength();
		var bytes = frame.getBytes();
		if (length == 0) {
			return; // Ignore.
		} else if (length < 2) {
			logger.warn("Received single-byte frame.");
			return; // Ignore.
		}
		var code = bytes[1] & 0xFF; // Command code.
		if (code == 0x2D) { // UAVCAN.
			if (length < 10) {
				logger.warn("Received truncated UAVCAN frame.");
				return; // Ignore.
			}
			var transferId = bytes[2] & 0x1F;
			var sourceId = bytes[3] & 0x7F;
			var priority = bytes[6] & 0x1F;
			var start = 7; // Payload start.
			var end = length - 3; // Payload end.
			if ((bytes[3] & 0x80) == 0) {
				if (sourceId == 0) {
					var typeId = bytes[4] & 0x03;
					broadcastHandler.handle(0, typeId, transferId, priority, bytes, start, end - start); // Anonymous broadcast.
				} else {
					var typeId = bytes[4] & 0xFF | (bytes[5] & 0xFF) << 8;
					broadcastHandler.handle(sourceId, typeId, transferId, priority, bytes, start, end - start); // Non-anonymous broadcast.
				}
			} else {
				var destinationId = bytes[4] & 0x7F;
				var typeId = bytes[5] & 0xFF;
				if ((bytes[4] & 0x80) == 0) {
					responseHandler.handle(sourceId, destinationId, typeId, transferId, priority, bytes, start, end - start);
				} else {
					requestHandler.handle(sourceId, destinationId, typeId, transferId, priority, bytes, start, end - start);
				}
			}
		} else {
			// Ignore any non-UAVCAN frames.
		}
	}

	public int inboxBacklog() {
		return serialDriver.inboxBacklog();
	}

	public int outboxBacklog() {
		return serialDriver.outboxBacklog();
	}

	public int collectorBacklog() {
		return serialDriver.collectorBacklog();
	}

	private static void checkPackable(int offset, int length) {
		if (offset < 0 || offset > 65535) {
			throw new IllegalArgumentException("Invalid offset."); // Offset too large to pack into "parameter pack" for writer.
		} else if (length < 0 || length > 245) {
			throw new IllegalArgumentException("Invalid length."); // Payload too long to fit into single frame.
		}
	}

	private static void writer(PartialFrame frame, long sequence, byte[] buffer, int messageId, int parameterPack) {
		int offset = parameterPack & 0xFFFF;
		int length = parameterPack >> 16 & 0xFF;
		int transferId = parameterPack >> 24 & 0x1F;
		writer(frame, sequence, messageId, transferId, buffer, offset, length);
	}

	private static void writer(PartialFrame frame, long sequence, Payload payload) {
		writer(frame, sequence, payload.messageId, payload.transferId, payload.buffer, payload.offset, payload.length);
	}

	private static void writer(PartialFrame frame, long sequence, int messageId, int transferId, byte[] buffer, int offset, int length) {
		frame.write((byte) 0x2D); // UAVCAN command code.
		frame.write((byte) transferId);
		frame.write((byte) (messageId & 0xFF));
		frame.write((byte) (messageId >> 8 & 0xFF));
		frame.write((byte) (messageId >> 16 & 0xFF));
		frame.write((byte) (messageId >> 24 & 0xFF));
		frame.write(buffer, offset, length);
	}

	private static class Payload {
		public final int messageId;
		public final int transferId;
		public final byte[] buffer;
		public final int offset;
		public final int length;

		public Payload(int messageId, int transferId, byte[] buffer, int offset, int length) {
			this.messageId = messageId;
			this.transferId = transferId;
			this.buffer = buffer;
			this.offset = offset;
			this.length = length;
		}
		
	}

}
