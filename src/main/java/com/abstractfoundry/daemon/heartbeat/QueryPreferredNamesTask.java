/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import com.abstractfoundry.daemon.uavcan.TypeId;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryPreferredNamesTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(QueryPreferredNamesTask.class);

	private final SerialConnectedNode daemonNode;
	private final Store store;
	private final Map<Integer, String> namesById = new HashMap<>();

	QueryPreferredNamesTask(SerialConnectedNode daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
	}
	
	@Override
	public void run() {
		for (var id : store.getConnectedIds()) {
			if (!namesById.containsKey(id)) {
				issueQuery(id);
			}
		}
	}

	private void issueQuery(int id) {
		try {
			logger.info("Querying preferred name for node: {}.", id);
			daemonNode.request(id, TypeId.GET_PREFERRED_NAME, 20, new byte[] {}, 0, 0,
				(error, buffer, offset, length) -> {
					if (error) {
						logger.warn("Error querying preferred name for node: {}.", id);
					}
					synchronized (this) {
						var name = new String(buffer, offset, length, StandardCharsets.UTF_8);
						logger.info("Preferred name for node {}: '{}'.", id, name);
						namesById.put(id, name);
						store.putPreferredName(id, name);
					}
				}
			);
		} catch (BackoffException exception) {
			logger.warn("Too busy to query preferred name for node: {}.", id);
		}
	}

}
