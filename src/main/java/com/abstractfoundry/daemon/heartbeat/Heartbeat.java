/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Heartbeat {

	private static final Logger logger = LoggerFactory.getLogger(Heartbeat.class);

	private final Runnable[] tasks;

	public Heartbeat(SerialConnectedNode daemonNode, Store store, ScriptExecutor scriptExecutor) {
		this.tasks = new Runnable[] {
			new AbortExpiredRequestsTask(daemonNode, 5),
			new SubscribeDefaultFieldsTask(daemonNode, store, 5),
			new QueryNodeInfoTask(daemonNode, store),
			new QueryPreferredNamesTask(daemonNode, store),
			new QueryMetadataTask(daemonNode, store),
			new PersistStoreTask(store, 120),
			new DisplayNetworkAddress(scriptExecutor, store),
			new SetBrightnessAndGamma(scriptExecutor, store),
			new StartMainScriptTask(scriptExecutor, store)
		};
	}

	public void pulse() {
		for (var task : tasks) {
			try {
				task.run();
			} catch (RuntimeException exception) {
				logger.error("Error running task: {}.", task.getClass().getName(), exception);
			}
		}
	}

}
