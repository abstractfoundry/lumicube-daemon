/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.BootstrapMetadata;
import com.abstractfoundry.daemon.bus.QueriedMetadata;
import com.abstractfoundry.daemon.bus.RecursiveDictionary;
import com.abstractfoundry.daemon.common.Pair;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import com.abstractfoundry.daemon.uavcan.TypeId;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryMetadataTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(QueryMetadataTask.class);

	private final SerialConnectedNode daemonNode;
	private final Store store;
	private final BootstrapMetadata bootstrapMetadata;
	private final Map<Integer, Pair<Integer, Integer>> queryKeysById = new HashMap<>();
	private final Map<Integer, RecursiveDictionary> accumulatedResultsById = new HashMap<>();

	QueryMetadataTask(SerialConnectedNode daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
		this.bootstrapMetadata = new BootstrapMetadata();
	}

	@Override
	public synchronized void run() {
		for (var id : store.getConnectedIds()) {
			if (!queryKeysById.containsKey(id)) {
				queryKeysById.put(id, new Pair<>(0, 0));
			}
			var keys = queryKeysById.get(id);
			var key0 = keys.getHead();
			var key1 = keys.getTail();
			if (key0 < 0 || key1 < 0) {
				continue; // This node ID is complete.
			}
			issueQuery(id, key0, key1);
		}
	}

	private synchronized void issueQuery(int id, int key0, int key1) {
		var query = new byte[] {
			0x12, (byte) (key0 & 0xFF), (byte) ((key0 >> 8) & 0xFF), // Skip to key0.
			0x01, 0x01, // 1 field follows.
			0x12, (byte) (key1 & 0xFF), (byte) ((key1 >> 8) & 0xFF) // Skip to key1.
		};
		try {
			daemonNode.request(id, TypeId.ENUMERATE_FIELDS, 20, query, 0, query.length,
				(error, buffer, offset, length) -> { // Note: This is called from a thread in the global pool.
					if (!error) {
						// Ignore, as we will try again later.
					}
					synchronized (this) {
						var results = RecursiveDictionary.deserialise(buffer, offset, length, bootstrapMetadata);
						var existing = accumulatedResultsById.putIfAbsent(id, results);
						if (existing != null) {
							var merged = existing.merge(results);
							accumulatedResultsById.put(id, merged);
						}
						try {
							var next0 = results.lastKey();
							var subdictionary = (RecursiveDictionary) results.get(next0);
							var next1 = subdictionary.lastKey() + 1;
							queryKeysById.put(id, new Pair<>(next0, next1));
							issueQuery(id, next0, next1); // Don't wait until the next heartbeat to issue the next query.
						} catch (NoSuchElementException exception) {
							queryKeysById.put(id, new Pair<>(-1, -1)); // Mark as completed.
							var metadata = new QueriedMetadata(accumulatedResultsById.get(id));
							store.putMetadata(id, metadata);
							logger.info("Fully accumulated metadata for node: {}.", id);
						}
					}
				}
			);
		} catch (BackoffException exception) {
			// Ignore, as we will try again later.
		}
	}

}
