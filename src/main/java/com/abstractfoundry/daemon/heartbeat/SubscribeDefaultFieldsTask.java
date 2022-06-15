/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import com.abstractfoundry.daemon.uavcan.TypeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SubscribeDefaultFieldsTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SubscribeDefaultFieldsTask.class);

	private static final int MAX_BANDWIDTH = 255;

	private final SerialConnectedNode daemonNode;
	private final Store store;
	private final int cadence;
	
	private int counter = 0;

	SubscribeDefaultFieldsTask(SerialConnectedNode daemonNode, Store store, int cadence) {
		this.daemonNode = daemonNode;
		this.store = store;
		this.cadence = cadence;
	}

	@Override
	public void run() {
		if (counter++ % cadence == 0) {
			var ids = store.getConnectedIds();
			logger.debug("Renewing subscription for node(s): {}.", ids);
			for (var id : ids) {
				try {
					var subscription = newSubscription(MAX_BANDWIDTH);
					daemonNode.request(id, TypeId.SUBSCRIBE_DEFAULT_FIELDS, 20, subscription, 0, subscription.length,
						(error, buffer, offset, length) -> {
							if (error) {
								logger.warn("Error renewing subscription for node: {}.", id);
							}
						}
					);
				} catch (BackoffException exception) {
					logger.warn("Too busy to renew subscription for node: {}.", id);
				}
			}
		}
	}

	public byte[] newSubscription(int bandwidth) {
		var seconds = cadence * 2; // Make the subscription for twice the renewal period, so it should never run out.
		return new byte[] { (byte) seconds, (byte) bandwidth };
	}
	
}
