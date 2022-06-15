/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.Builder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Allocator {

	// TODO: Update allocation table with observed nodes' self or previously assigned IDs (see CentralisedServer in old implementation).

	private static final Logger logger = LoggerFactory.getLogger(Allocator.class);

	private static final Duration QUERY_TIMEOUT = Duration.ofMillis(500);

	private static class AllocationTable {

		private final Map<Integer, UUID> map = new HashMap<>(); // Note: UUID may be NULL in order to simply claim ID.

		public void allocate(int nodeId, UUID uuid) {
			if (nodeId < 1 || nodeId > 127) {
				throw new IllegalArgumentException("Invalid node ID.");
			} else if (map.containsKey(nodeId)) {
				throw new IllegalStateException("Node ID already allocated.");
			} else if (map.containsValue(uuid)) {
					throw new IllegalStateException("UUID already allocated.");
			}
			map.put(nodeId, uuid);
		}

		public int getNodeId(UUID uuid) {
			if (uuid == null) {
				throw new IllegalArgumentException("Invalid UUID.");
			}
			for (var entry : map.entrySet()) {
				var allocatedUuid = entry.getValue();
				if (allocatedUuid != null && allocatedUuid.equals(uuid)) {
					return entry.getKey(); // Allocated.
				}
			}
			return 0; // Unallocated.
		}

		public boolean isAllocated(int nodeId) {
			if (nodeId < 1 || nodeId > 127) {
				throw new IllegalArgumentException("Invalid node ID.");
			}
			return map.containsKey(nodeId);
		}

	}

	private final Node node;
	private final AllocationTable table = new AllocationTable();
	private final int priority = 20; // Note: Priority given to allocation broadcasts.
	private final byte[] exchange = new byte[17]; // 1-byte header and 16-byte UUID.
	private int cursor = 0;
	private Instant timestamp = Instant.MIN;

	public Allocator(Node node) {
		this.node = node;
	}

	// Note: Currently we only call this method from the inbox Disruptor thread, although it is synchronised for future safety.
	synchronized void handleMessage(int sourceId, byte[] buffer, int offset, int length) {
		var now = Instant.now();
		if (sourceId != 0) {
			logger.warn("More than one allocator exists on this network.");
			return;
		}
		// TODO: Wait until we have discovered the IDs of all nodes with self or previously assigned IDs.
		var elapsed = Duration.between(timestamp, now);
		if (cursor > 0 && elapsed.compareTo(QUERY_TIMEOUT) > 0) {
			logger.info("Allocation timeout.");
			cursor = 0;
		}
		var newQuery = (buffer[offset] & 0x01) != 0;
		if (newQuery && length == 7) {
			handleFirstAllocationStage(buffer, offset, length);
		} else if (cursor == 7 && length == 7) {
			handleSecondAllocationStage(buffer, offset, length);
		} else if (cursor == 13 && length == 5) {
			handleFinalAllocationStage(buffer, offset, length);
		} else {
			logger.warn("Invalid allocation query.");
		}
	}

	private void handleFirstAllocationStage(byte[] buffer, int offset, int length) {
		timestamp = Instant.now();
		exchange[0] = 0; // Allocation incomplete.
		System.arraycopy(buffer, offset + 1, exchange, 1, 6);
		cursor = 7;
		node.broadcast(TypeId.ALLOCATION, priority, exchange, 0, cursor);
		logger.debug("Completed first allocation stage.");
	}

	private void handleSecondAllocationStage(byte[] buffer, int offset, int length) {
		timestamp = Instant.now();
		System.arraycopy(buffer, offset + 1, exchange, 7, 6);
		cursor = 13;
		node.broadcast(TypeId.ALLOCATION, priority, exchange, 0, cursor);
		logger.debug("Completed second allocation stage.");
	}

	private void handleFinalAllocationStage(byte[] buffer, int offset, int length) {
		timestamp = Instant.now();
		System.arraycopy(buffer, offset + 1, exchange, 13, 4);
		cursor = 17;
		var uuid = Builder.uuid(exchange, 1, 16);
		var requestedId = (buffer[offset] & 0xFE) >> 1;
		logger.info("Allocatee UUID: {}.", uuid);
		logger.info("Allocatee requested ID: {}.", requestedId);
		var allocatedId = allocateId(requestedId, uuid);
		if (allocatedId != 0) {
			logger.info("Allocatee given ID: {}.", allocatedId);
			int header = (allocatedId << 1) & 0xFE;
			exchange[0] = (byte) header;
			node.broadcast(TypeId.ALLOCATION, priority, exchange, 0, cursor);
			cursor = 0;
			logger.debug("Completed final allocation stage.");
		} else {
			logger.error("Node ID exhaustion.");
		}
	}

	private int allocateId(int requestedId, UUID uuid) {
		var id = table.getNodeId(uuid);
		if (id == 0 && requestedId == 0) {
			for (int candidate = 125; candidate > 0; candidate--) {
				if (!table.isAllocated(candidate)) {
					id = candidate;
					break;
				}
			}
			if (id != 0) {
				table.allocate(id, uuid);
			}
		} else if (id == 0 && requestedId != 0) {
			throw new UnsupportedOperationException("Not supported yet."); // TODO: Allocate the first ID equal to or higher than the requested ID.
		}
		return id;
	}

}
