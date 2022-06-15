/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PersistStoreTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(PersistStoreTask.class);

	private final Store store;
	private final int cadence;
	
	private int counter = 0;

	PersistStoreTask(Store store, int cadence) {
		this.store = store;
		this.cadence = cadence;
	}

	@Override
	public void run() {
		if (counter++ % cadence == 0) {
			store.persist();
			logger.info("Persisting store to disk.");
		}
	}

}
