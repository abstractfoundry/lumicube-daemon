/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.uavcan.Node;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SocketChannelClientHandler implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SocketChannelClientHandler.class);

	private final SocketChannel socketChannel;
	private final Selector readSelector;
	private final Selector writeSelector;
	private final GenericSocketClientHandler genericHandler;
	private final byte[] readQueue = new byte[4096];
	private final byte[] writeQueue = new byte[4096];
	private final ByteBuffer readBuffer = ByteBuffer.wrap(readQueue);
	private final ByteBuffer writeBuffer = ByteBuffer.wrap(writeQueue);
	private int readCursor = 0, readLimit = 0;
	private int writeLimit = 0;
	
	private final byte[] commandBytes = new byte[32*256*256]; // i.e. 2048 KB.
	private final ByteBuffer commandBuffer = ByteBuffer.wrap(commandBytes);
	private int commandCursor = 0;
	private boolean commandPending = false;

	private final Object transmissionLock;
	private final TransmissionFunction transmissionFunction;

	private final AtomicBoolean abort = new AtomicBoolean(false);

	/**
	 * Handler for socket channel clients.
	 * The implementation of this class is complicated, manually making use of
	 * selectors etc. since Java's Channels.newInputStream and .newOutputStream
	 * do not support simultaneous reading and writing from a blocking channel
	 * (only fixed in Java 19: https://bugs.openjdk.java.net/browse/JDK-8279339)
	 * so we must implement our own buffering and blocking logic on top of the
	 * asynchronous channel API. This also offers us a (currently unused) benefit
	 * of waking read operations using the read selector's .wakeup method.
	 */
	SocketChannelClientHandler(Node daemonNode, SocketChannel socketChannel, GenericSocketClientHandler genericHandler) {
		Selector readSelector = null, writeSelector = null;
		try {
			readSelector = Selector.open();
			writeSelector = Selector.open();
			socketChannel.configureBlocking(false); // The selector blocks, not the channel.
			socketChannel.register(readSelector, SelectionKey.OP_READ);
			socketChannel.register(writeSelector, SelectionKey.OP_WRITE);
		} catch (IOException | RuntimeException exception) {
			try { if (readSelector != null) readSelector.close(); } catch (IOException ignored) {}
			try { if (writeSelector != null) writeSelector.close(); } catch (IOException ignored) {}
			try { if (socketChannel != null) socketChannel.close(); } catch (IOException ignored) {}
			throw new IllegalStateException("Failed to create handler.");
		}
		this.socketChannel = socketChannel;
		this.readSelector = readSelector;
		this.writeSelector = writeSelector;
		this.genericHandler = genericHandler;
		readBuffer.order(ByteOrder.LITTLE_ENDIAN);
		writeBuffer.order(ByteOrder.LITTLE_ENDIAN);
		commandBuffer.order(ByteOrder.LITTLE_ENDIAN);
		transmissionLock = new Object(); // Only allow one concurrent transmitter.
		transmissionFunction = buffer -> {
			synchronized (this.transmissionLock) {
				var length = buffer.limit();
				try {
					write(buffer.array(), 0, length);
					flush();
				} catch (IOException | ClosedSelectorException exception) {
					abort.set(true); // Abort client on I/O or any runtime error.
				} catch (RuntimeException exception) {
					abort.set(true); // Abort client on I/O or any runtime error.
					throw exception;
				} finally {
					buffer.position(length); // Indicate buffer has been read (discard data on error).
				}
			}
		};
	}


	private void loop() throws IOException {
		if (!commandPending) { // Currently we wastefully idle if the daemon is too busy to accept the command.
			if (commandCursor < 4) { // Fill in the command length (4 bytes).
				commandCursor += read(commandBytes, commandCursor, 4 - commandCursor); // If we have been woken from the blocking read operation, this could return without reading any or all of the bytes.
			} else {
				if (commandBytes[2] >= 0x20 || commandBytes[3] != 0x00) {
					throw new IllegalStateException("Oversize command"); // Commands over 2048 KB are not currently supported.
				}
				var length = (commandBytes[0] & 0xFF) | (commandBytes[1] & 0xFF) << 8 | (commandBytes[2] & 0xFF) << 16 | (commandBytes[3] & 0xFF) << 24;
				commandCursor += read(commandBytes, commandCursor, length - commandCursor); // If we have been woken from the blocking read operation, this could return without reading any or all of the bytes.
				if (commandCursor > length) {
					throw new IllegalStateException("Indexing error.");
				} else if (commandCursor == length) {
					commandPending = true;
				}
			}
		}
		if (commandPending) {
			var retry = false; // Set to true in order to retry the command again.
			var length = (commandBytes[0] & 0xFF) | (commandBytes[1] & 0xFF) << 8 | (commandBytes[2] & 0xFF) << 16 | (commandBytes[3] & 0xFF) << 24;
			commandBuffer.limit(length);
			commandBuffer.position(0);
			try {
				retry = genericHandler.handle(commandBuffer, transmissionFunction);
			} catch (RuntimeException exception) {
				logger.error("Error processing command.", exception); // TODO: Send the error back to the client (also allowing the client to reclaim the correlation ID).
			} finally {
				if (retry) {
					LockSupport.parkNanos(10_000_000L); // Wait at least 10 ms to avoid starving the inbox thread. // TODO: We should use random exponential backoff (and also perhaps write something to the log).
				} else {
					commandCursor = 0;
					commandPending = false;
				}
			}
		}
	}

	private void spin() {
		try {
			while (abort.get() == false && !Thread.interrupted()) {
				loop();
			}
		} catch (IOException exception) {
			// Most likely the client just closed the channel, so don't report the stack trace.
		} finally {
			logger.info("Closing client socket.");
		}
	}

	@Override
	public void run() {
		try (socketChannel; readSelector; writeSelector) { // Auto-close.
			spin();
		} catch (IOException exception) {
			// The only I/O exceptions caught here are those from attempting to auto-close the socket, streams and buffers, which can simply be ignored.
		} catch (RuntimeException exception) {
			logger.error("Unhandled exception.", exception);
		}
	}

	// Not thread-safe, caller must apply caution.
	private int read(byte[] buffer, int offset, int length) throws IOException { // This blocking method can be woken by calling .wakeup() on the readSelector.
		if (buffer == null || offset < 0 || length < 0 || offset + length > buffer.length) {
			throw new IllegalArgumentException("Invalid arguments.");
		}
		if (readCursor == readLimit) { // No useful data in the buffer, reset it.
			readCursor = readLimit = 0;
		}
		if (length > readLimit - readCursor) { // If we don't have enough data then block until at least some more data is read / we are awoken, else return the data already buffered.
			fill();
		}
		var chunk = Math.min(length, readLimit - readCursor);
		System.arraycopy(readQueue, readCursor, buffer, offset, chunk);
		readCursor += chunk;
		return chunk;
	}

	// Not thread-safe, caller must apply caution.
	private void write(byte[] buffer, int offset, int length) throws IOException {
		if (buffer == null || offset < 0 || length < 0 || offset + length > buffer.length) {
			throw new IllegalArgumentException("Invalid arguments.");
		}
		var cursor = offset;
		while (cursor < offset + length) {
			var chunk = Math.min(offset + length - cursor, writeQueue.length - writeLimit);
			System.arraycopy(buffer, cursor, writeQueue, writeLimit, chunk);
			cursor += chunk;
			writeLimit += chunk;
			if (writeLimit == writeQueue.length) {
				flush();
			}
		}
	}

	private Consumer<SelectionKey> readCallback = new Consumer<>() {
		@Override
		public void accept(SelectionKey key) {
			if (key.isValid() && key.isReadable() && key.channel() == socketChannel) {
				try {
					var count = socketChannel.read(readBuffer);
					if (count < 0) {
						throw new IOException("End of stream.");
					}
				} catch (IOException exception) {
					throw new RuntimeException(exception); // Key consumer can only throw unchecked exceptions.
				}
			} else {
				throw new IllegalStateException("Unexpected read selector key.");
			}
		}
	};

	// Not thread-safe, caller must apply caution.
	private void fill() throws IOException { // This blocking method can be woken by calling .wakeup() on the readSelector.
		try {
			readBuffer.limit(readQueue.length);
			readBuffer.position(readLimit);
			readSelector.select(readCallback); // Use this callback "key consumer" method, as it creates less / no garbage, compared to an iterator.
		} catch (RuntimeException exception) {
			var cause = exception.getCause();
			if (cause != null && cause instanceof IOException) {
				throw (IOException) cause;
			} else {
				throw exception;
			}
		} finally {
			readLimit = readBuffer.position();
		}
	}

	private Consumer<SelectionKey> writeCallback = new Consumer<>() {
		@Override
		public void accept(SelectionKey key) {
			if (key.isValid() && key.isWritable() && key.channel() == socketChannel) {
				try {
					var written = socketChannel.write(writeBuffer);
					if (written < 0) {
						throw new IllegalStateException("Unexpected channel write return code.");
					}
				} catch (IOException exception) {
					throw new RuntimeException(exception); // Key consumer can only throw unchecked exceptions.
				}
			} else {
				throw new IllegalStateException("Unexpected write selector key.");
			}
		}
	};

	// Not thread-safe, caller must apply caution.
	private void flush() throws IOException { // This could block indefinitely, stalling this handler if the client is not properly reading from the socket.
		try {
			writeBuffer.limit(writeLimit);
			writeBuffer.position(0);
			while (writeBuffer.remaining() > 0) { // Unlike the readSelector, if the writeSelector is woken-up we continue writing until the flush is complete.
				writeSelector.select(writeCallback); // Use this callback "key consumer" method, as it creates less / no garbage, compared to an iterator.
			}
		} catch (RuntimeException exception) {
			var cause = exception.getCause();
			if (cause != null && cause instanceof IOException) {
				throw (IOException) cause;
			} else {
				throw exception;
			}
		} finally {
			writeLimit = 0; // Discard data on error.
		}
	}

}
