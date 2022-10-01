/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.common.AsciiView;
import com.abstractfoundry.daemon.common.Lazy;
import com.abstractfoundry.daemon.common.SimpleObjectPool;
import com.abstractfoundry.daemon.python.service.GlobalPythonService;
import com.abstractfoundry.daemon.server.method.node.display.DisplaySetMethod;
import com.abstractfoundry.daemon.server.method.node.GetFieldsMethod;
import com.abstractfoundry.daemon.server.method.node.SetFieldsMethod;
import com.abstractfoundry.daemon.server.method.node.screen.ScreenDrawRectangleMethod;
import com.abstractfoundry.daemon.server.method.node.screen.ScreenSetHalfRowMethod;
import com.abstractfoundry.daemon.server.method.node.screen.ScreenWriteTextMethod;
import com.abstractfoundry.daemon.uavcan.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericSocketClientHandler {

	private static final Logger logger = LoggerFactory.getLogger(GenericSocketClientHandler.class);

	private final Node daemonNode;
	private final ExecutorService globalPool;
	private final Store store;
	private final GlobalPythonService globalPythonService;
	
	private final AsciiView moduleNameView = new AsciiView();
	private final AsciiView methodNameView = new AsciiView();
	private final AsciiView jsonView = new AsciiView();

	private final SimpleObjectPool<PooledEmptyReplyTask> emptyReplyPool;

	private final Lazy<DisplaySetMethod> displaySetMethod;
	private final Lazy<ScreenSetHalfRowMethod> screenSetHalfRowMethod;
	private final Lazy<ScreenDrawRectangleMethod> screenDrawRectangleMethod;
	private final Lazy<ScreenWriteTextMethod> screenWriteTextMethod;
	private final Lazy<GetFieldsMethod> getFieldsMethod;
	private final Lazy<SetFieldsMethod> setFieldsMethod;
	
	// TODO: Initially wait for the client to "open" the connection (command code 0x00) with an explicit protocol version number?

	public GenericSocketClientHandler(Node daemonNode, ExecutorService globalPool, Store store, GlobalPythonService globalPythonService) {
		this.daemonNode = daemonNode;
		this.globalPool = globalPool;
		this.store = store;
		this.globalPythonService = globalPythonService;
		this.emptyReplyPool = new SimpleObjectPool<>(64,
			slot -> new PooledEmptyReplyTask(slot)
		);
		this.displaySetMethod = new Lazy<>(() -> new DisplaySetMethod(this.daemonNode, this.store));
		this.screenSetHalfRowMethod = new Lazy<>(() -> new ScreenSetHalfRowMethod(this.daemonNode, this.store));
		this.screenDrawRectangleMethod = new Lazy<>(() -> new ScreenDrawRectangleMethod(this.daemonNode, this.store));
		this.screenWriteTextMethod = new Lazy<>(() -> new ScreenWriteTextMethod(this.daemonNode, this.store));
		this.getFieldsMethod = new Lazy<>(() -> new GetFieldsMethod(this.daemonNode, this.store));
		this.setFieldsMethod = new Lazy<>(() -> new SetFieldsMethod(this.daemonNode, this.store));
	}

	public boolean handle(ByteBuffer buffer, TransmissionFunction transmissionFunction) { // TODO: Currently uncaught exceptions cause that correlation number to never receive a reply. If we were to reply with an empty message to any uncaught exceptions that could cause duplicate replies, since the request might already have been dispatched and the continuation may reply also. Think of a way to handle this properly.
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		var length = buffer.limit();
		var retry = false;
		var correlationNumber = buffer.getShort(4) & 0xFFFF; // Cast to unsigned.
		var commandCode = buffer.getShort(6) & 0xFFFF; // Cast to unsigned.
		if (commandCode == 0x10) { // Command = Method.
			var type = buffer.get(8) & 0xFF;
			if (type == 0x01) { // Type = Module.
				var nodeNameLength = buffer.get(9) & 0xFF;
				moduleNameView.set(buffer, 10, nodeNameLength);
				var methodNameLength = buffer.get(10 + nodeNameLength) & 0xFF;
				methodNameView.set(buffer, 11 + nodeNameLength, methodNameLength);
				var payloadStart = 11 + nodeNameLength + methodNameLength;
				var payloadLength = length - payloadStart;
				jsonView.set(buffer, payloadStart, payloadLength);
				if (CharSequence.compare(moduleNameView, "display") == 0 && CharSequence.compare(methodNameView, "set") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = displaySetMethod.get(); // TODO: Common up this logic with the following methods.
					method.parse(jsonView);
					try {
						var replyTaskSlot = emptyReplyPool.claim();
						final var replyTask = replyTaskSlot.instance;
						replyTask.set(transmissionFunction, correlationNumber);
						var issued = false;
						try {
							issued = method.invoke(replyTask, replyTask);
						} finally {
							if (!issued) {
								retry = true;
								replyTaskSlot.release();
							}
						}
					} catch (SimpleObjectPool.PoolExhaustedException exception) {
						retry = true;
					}
				} else if (CharSequence.compare(moduleNameView, "screen") == 0 && CharSequence.compare(methodNameView, "set_half_row") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = screenSetHalfRowMethod.get();
					method.parse(jsonView);
					try {
						var replyTaskSlot = emptyReplyPool.claim();
						final var replyTask = replyTaskSlot.instance;
						replyTask.set(transmissionFunction, correlationNumber);
						var issued = false;
						try {
							issued = method.invoke(replyTask, replyTask);
						} finally {
							if (!issued) {
								retry = true;
								replyTaskSlot.release();
							}
						}
					} catch (SimpleObjectPool.PoolExhaustedException exception) {
						retry = true;
					}
				} else if (CharSequence.compare(moduleNameView, "screen") == 0 && CharSequence.compare(methodNameView, "draw_rectangle") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = screenDrawRectangleMethod.get();
					method.parse(jsonView);
					try {
						var replyTaskSlot = emptyReplyPool.claim();
						final var replyTask = replyTaskSlot.instance;
						replyTask.set(transmissionFunction, correlationNumber);
						var issued = false;
						try {
							issued = method.invoke(replyTask, replyTask);
						} finally {
							if (!issued) {
								retry = true;
								replyTaskSlot.release();
							}
						}
					} catch (SimpleObjectPool.PoolExhaustedException exception) {
						retry = true;
					}
				} else if (CharSequence.compare(moduleNameView, "screen") == 0 && CharSequence.compare(methodNameView, "write_text") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = screenWriteTextMethod.get();
					method.parse(jsonView);
					try {
						var replyTaskSlot = emptyReplyPool.claim();
						final var replyTask = replyTaskSlot.instance;
						replyTask.set(transmissionFunction, correlationNumber);
						var issued = false;
						try {
							issued = method.invoke(replyTask, replyTask);
						} finally {
							if (!issued) {
								retry = true;
								replyTaskSlot.release();
							}
						}
					} catch (SimpleObjectPool.PoolExhaustedException exception) {
						retry = true;
					}
				} else if (CharSequence.compare(methodNameView, "set_fields") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = setFieldsMethod.get();
					method.parse(moduleNameView.toString(), jsonView);
					try {
						var replyTaskSlot = emptyReplyPool.claim();
						final var replyTask = replyTaskSlot.instance;
						replyTask.set(transmissionFunction, correlationNumber);
						var issued = false;
						try {
							issued = method.invoke(replyTask, replyTask);
						} finally {
							if (!issued) {
								retry = true;
								replyTaskSlot.release();
							}
						}
					} catch (SimpleObjectPool.PoolExhaustedException exception) {
						retry = true;
					}
				} else if (CharSequence.compare(methodNameView, "get_fields") == 0) { // TODO: Dispatch based on the dynamic namespace.
					var method = getFieldsMethod.get();
					method.parse(moduleNameView.toString(), jsonView);
					var responder = new Consumer<CharSequence>() { // TODO: Pool responder instances, especially since they are expensive.
						private final ByteBuffer buffer = ByteBuffer.allocate(4096); // TODO: Statically allocate?
						@Override
						public void accept(CharSequence payload) {
							try {
								buffer.order(ByteOrder.LITTLE_ENDIAN);
								var encoded = StandardCharsets.US_ASCII.encode( // Note: Currently we encode strings sent over the socket as ASCII, and not UTF-8 (c.f. AsciiView). // TODO: Encode without creating garbage?
									CharBuffer.wrap(payload)
								);
								var length = encoded.limit();
								buffer.putInt(0, 6 + length); // Header and payload length.
								buffer.putShort(4, (short) correlationNumber); // Correlation number.
								// TODO: Prefix payload with some kind of command code / error code?
								buffer.put(6, encoded.array(), 0, length);
								buffer.limit(6 + length);
								buffer.position(0);
								transmissionFunction.transmit(buffer);
							} catch (IOException exception) {
								// Most likely the client just closed the channel, so don't report the stack trace.
							}
						}
					};
					retry = !method.invoke(responder);
				} else {
					final var moduleName = moduleNameView.toString(); // Materialise as constant string for task closure.
					final var methodName= methodNameView.toString();
					final var json = jsonView.toString();
					globalPool.submit(() -> { // Invoke method in global pool, as it might be long-running, and recursively spawn additional requests to this handler (so we must not deadlock).
						try {
							Map response = new HashMap();
							ByteBuffer encoded;
							try {
								var result = globalPythonService.invokeModuleMethod(moduleName, methodName, json); // Fall back to the Python implementation of the method.
								response.put("status", 0);
								response.put("result", result);
							} catch (RuntimeException exception) {
								logger.error("Python service error.", exception);
								response.put("status", -1);
								response.put("error", exception.getMessage()); // TODO: Unify our approach to result / error reporting, e.g. see comment in get_fields above.
							}
							var serialiser = new ObjectMapper();
							try {
								var serialised = serialiser.writeValueAsString(response);
								encoded = StandardCharsets.US_ASCII.encode( // Note: Currently we encode strings sent over the socket as ASCII, and not UTF-8 (c.f. AsciiView).
									CharBuffer.wrap(serialised)
								);
							} catch (JsonProcessingException exception) {
								throw new RuntimeException("Error serialising result.", exception);
							}
							var encodedLength = encoded.limit();
							var replyBuffer = ByteBuffer.allocate(6 + encodedLength);
							replyBuffer.order(ByteOrder.LITTLE_ENDIAN);
							replyBuffer.putInt(0, 6 + encodedLength); // Reply length.
							replyBuffer.putShort(4, (short) correlationNumber); // Correlation number.
							// TODO: Prefix payload with some kind of command code / error code?
							replyBuffer.put(6, encoded.array(), 0, encodedLength);
							replyBuffer.limit(6 + encodedLength);
							replyBuffer.position(0);
							transmissionFunction.transmit(replyBuffer);
						} catch (IOException | RuntimeException exception) {
							logger.error("Unhandled error invoking method.", exception);
						}
					});
				}
				buffer.position(length); // Indicate the buffer has been read.
			} else {
				throw new UnsupportedOperationException("Unsupported type.");
			}
		} else {
			throw new UnsupportedOperationException("Unsupported command.");
		}
		return retry;
	}

	private class PooledEmptyReplyTask implements Runnable {

		SimpleObjectPool<? extends PooledEmptyReplyTask>.Slot slot;
		private final ByteBuffer buffer = ByteBuffer.allocate(6);
		private TransmissionFunction transmissionFunction;
		private int correlationNumber = -1;

		public PooledEmptyReplyTask(SimpleObjectPool<? extends PooledEmptyReplyTask>.Slot slot) {
			this.slot = slot;
			buffer.order(ByteOrder.LITTLE_ENDIAN);
		}

		public final void reset() {
			transmissionFunction = null;
			correlationNumber = -1;
		}

		public final void set(TransmissionFunction transmissionFunction, int correlationNumber) {
			this.transmissionFunction = transmissionFunction;
			this.correlationNumber = correlationNumber;
		}

		@Override
		public void run() {
			try {
				buffer.putInt(0, 6); // Reply length.
				buffer.putShort(4, (short) correlationNumber); // Correlation number.
				buffer.limit(6);
				buffer.position(0);
				transmissionFunction.transmit(buffer);
			} catch (IOException exception) {
				// Most likely the client just closed the channel, so don't report the stack trace.
			} finally {
				reset();
				slot.release();
			}
		}

	}

}
