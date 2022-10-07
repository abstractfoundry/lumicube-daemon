/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.NodeInfo;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import com.abstractfoundry.daemon.uavcan.TypeId;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryNodeInfoTask implements Runnable { // TODO: Update the allocator with the UUIDs associated with previously assigned IDs?

	private static final Logger logger = LoggerFactory.getLogger(QueryNodeInfoTask.class);

	private final SerialConnectedNode daemonNode;
	private final Store store;
	private final Map<Integer, NodeInfo> infoById = new HashMap<>();

	QueryNodeInfoTask(SerialConnectedNode daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
	}

	@Override
	public void run() {
		for (var id : store.getConnectedIds()) {
			if (!infoById.containsKey(id)) {
				issueQuery(id);
			}
		}
	}

	private void issueQuery(int id) {
		try {
			logger.info("Querying node information for: {}.", id);
			daemonNode.request(id, TypeId.GET_NODE_INFO, 20, new byte[] {}, 0, 0,
				(error, buffer, offset, length) -> {
					if (error) {
						logger.warn("Error querying node information for: {}.", id);
					}
					synchronized (this) {
						var info = NodeInfo.deserialise(buffer, offset, length);
						logger.info("Node information for {}: UUID = {}; Name = '{}'.", id, info.getUuid(), info.getName());
						infoById.put(id, info);
						store.putNodeInfo(id, info);
					}
				}
			);
		} catch (BackoffException exception) {
			logger.warn("Too busy to query node information for: {}.", id);
		}
	}

}
