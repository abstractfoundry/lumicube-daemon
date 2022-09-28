/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.ThreadSafe;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Node {

	/* Warning: The receive methods of this class must only be called from a
	 * dedicated thread (generally the Disruptor thread for handling incoming
	 * messages). Therefore all internal state transitions of the node, which
	 * are driven off received messages, are essentially single-threaded.
	 * However, care must be taken to ensure that client-thread interactions
	 * are handled in a thread-safe manner. */

	private static final Logger logger = LoggerFactory.getLogger(Node.class);

	private final BroadcastTable broadcastTable = new BroadcastTable(65536); // One slot for each 16-bit type, holding the next transfer ID.
	private final ServiceTable serviceTable = new ServiceTable(32768, 5000); // One slot for each 8-bit type and 7-bit destination pair, holding 32-bits which represent the transfer IDs in flight, expire after 5 seconds.
	private final int selfId;
	private ExecutorService globalPool;
	private BroadcastHandler broadcastHandler;
	private final Allocator allocator;

	protected Node(int selfId, ExecutorService globalPool, BroadcastHandler broadcastHandler) {
		if (selfId < 1 || selfId > 127) {
			throw new IllegalArgumentException("Invalid node ID."); // The daemon must not be anonymous, since it is an allocator.
		}
		this.selfId = selfId;
		this.globalPool = globalPool;
		this.broadcastHandler = broadcastHandler;
		this.allocator = new Allocator(this);
	}

	/**
	 * Thread-safe method used by client threads to send a broadcast.
	 */
	@ThreadSafe
	public void broadcast(int typeId, int priority, byte[] buffer, int offset, int length) {
		if (buffer == null) {
			throw new IllegalArgumentException("Invalid buffer.");
		}
		var transferId = nextBroadcastTransferId(typeId);
		sendBroadcast(selfId, typeId, transferId, priority, buffer, offset, length);
	}

	/**
	 * Thread-safe method used by client threads to send a request.
	 * <p>
	 * Caution: The callback will be executed in a different thread to the requester (for example a thread from the global pool).
	 * Note: This method generates some small amount of garbage, provide your own (potentially pooled) continuation to avoid this.
	 */
	@ThreadSafe
	public void request(int destinationId, int typeId, int priority, byte[] buffer, int offset, int length, CallbackContinuation.Callback callback) throws BackoffException {
		request(destinationId, typeId, priority, buffer, offset, length, new CallbackContinuation(callback));
	}

	/**
	 * Thread-safe method used by client threads to send a request.
	 */
	@ThreadSafe
	public void request(int destinationId, int typeId, int priority, byte[] buffer, int offset, int length, AbstractContinuation continuation) throws BackoffException {
		if (buffer == null || offset + length > buffer.length || continuation == null) {
			throw new IllegalArgumentException("Invalid arguments.");
		}
		var timestamp = System.nanoTime();
		try {
			var transferId = claimServiceTransferId(destinationId, typeId, timestamp, continuation);
			sendRequest(selfId, destinationId, typeId, transferId, priority, buffer, offset, length);
		} catch (ServiceTable.KeyExhaustedException exception) {
			throw new BackoffException("Too many concurrent requests in flight.");
		}
	}

	/**
	 * Thread-safe method used by client threads to send several requests.
	 */
	@ThreadSafe
	public void request(int destinationId, int typeId, int priority, ByteBuffer[] buffers, int count, AbstractContinuation continuation) throws BackoffException {
		if (buffers == null || count > buffers.length || continuation == null) {
			throw new IllegalArgumentException("Invalid arguments.");
		}
		var timestamp = System.nanoTime(); // In this scenario, where the same continuation object is associated with multiple TIDs, it is possible that the continuation expires on one TID, and is then returned to the pool before the other associated TID expires, unless we mark all associated TIDs with the same timestamp, such that they all expire together under the same atomic operation on the service table.
		var transferIds = new int[count]; // This generates some small amount of garbage, but it should be easily offset by the benefit of batching.
		for (var index = 0; index < count; index++) {
			try {
				transferIds[index] = claimServiceTransferId(destinationId, typeId, timestamp, continuation);
			} catch (ServiceTable.KeyExhaustedException exception) {
				for (var unwind = 0; unwind < index - 1; unwind++) {
					try {
						releaseServiceTransferId(destinationId, typeId, transferIds[unwind]);
					} catch (ServiceTable.KeyUnclaimedException error) {
						logger.error("Failed to release transfer ID whilst unwinding request.", error);
					}
				}
				throw new BackoffException("Too many concurrent requests in flight.");
			}
		}
		sendRequests(selfId, destinationId, typeId, transferIds, priority, buffers, count);
	}

	protected void handleBroadcast(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		switch (typeId) {
			case TypeId.ALLOCATION:
				allocator.handleMessage(sourceId, buffer, offset, length);
				break;
			default:
				broadcastHandler.handle(sourceId, typeId, transferId, priority, buffer, offset, length);
				break;
		}
	}

	protected void handleRequest(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	protected void handleResponse(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		if (SIMULATE_LOSS && lossSimulationRandom.nextDouble() < SIMULATED_LOSS_PROBABILITY) {
			return;
		}
		try {
			var continuation = releaseServiceTransferId(sourceId, typeId, transferId);
			Runnable task = continuation.next(buffer, offset, length); // Get the task to be continued.
			if (task != null) {
				globalPool.submit(task); // Issue the task to the global pool, to avoid blocking the inbox disruptor thread.
			}
		} catch (ServiceTable.KeyUnclaimedException exception) {
			logger.warn("Unexpected response from ID: {}.", sourceId);
		}
	}

	private static final boolean SIMULATE_LOSS = false;
	private static final double SIMULATED_LOSS_PROBABILITY = 0.25 / 100.0;
	private final Random lossSimulationRandom = new Random();

	@ThreadSafe
	private int nextBroadcastTransferId(int typeId) {
		var key = buildBroadcastTableKey(typeId);
		return broadcastTable.next(key);
	}

	@ThreadSafe
	private int claimServiceTransferId(int counterpartyId, int typeId, long timestamp, AbstractContinuation continuation) throws ServiceTable.KeyExhaustedException {
		var key = buildServiceTableKey(counterpartyId, typeId);
		return serviceTable.claim(key, timestamp, continuation);
	}

	@ThreadSafe
	private AbstractContinuation releaseServiceTransferId(int counterpartyId, int typeId, int transferId) throws ServiceTable.KeyUnclaimedException {
		var key = buildServiceTableKey(counterpartyId, typeId);
		return serviceTable.release(key, transferId);
	}

	@ThreadSafe
	private int buildBroadcastTableKey(int typeId) {
		if (typeId < 0 || typeId > 0xFFFF) {
			throw new IllegalArgumentException("Invalid type.");
		}
		return (typeId & 0xFFFF);
	}

	@ThreadSafe
	private int buildServiceTableKey(int destinationId, int typeId) {
		if (destinationId < 1 || destinationId > 127) {
			throw new IllegalArgumentException("Invalid destination ID.");
		} else if (typeId < 0 || typeId > 0xFF) {
			throw new IllegalArgumentException("Invalid type.");
		}
		return (typeId & 0xFF) | (destinationId & 0x7F) << 8;
	}

	protected void receiveBroadcast(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		handleBroadcast(sourceId, typeId, transferId, priority, buffer, offset, length);
	}

	protected void receiveRequest(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		if (destinationId == selfId) {
			handleRequest(sourceId, typeId, transferId, priority, buffer, offset, length);
		}
	}

	protected void receiveResponse(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		if (destinationId == selfId) {
			handleResponse(sourceId, typeId, transferId, priority, buffer, offset, length);
		}
	}

	@ThreadSafe
	protected abstract void sendBroadcast(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length);

	@ThreadSafe
	protected abstract void sendRequest(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length);

	@ThreadSafe
	protected abstract void sendRequests(int sourceId, int destinationId, int typeId, int[] transferIds, int priority, ByteBuffer[] buffers, int count);

	@ThreadSafe
	protected abstract void sendResponse(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length);

	public int abortExpiredRequests() {
		return serviceTable.abortExpired(globalPool);
	}

	public int outstandingRequests() {
		return serviceTable.occupancyCount();
	}

	public abstract void start();

}
