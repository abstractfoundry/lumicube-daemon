/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import com.abstractfoundry.daemon.common.Pair;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch of bytes to pass to another thread.
 * The principle of operation is revolves around a pair of batch objects, where
 * one thread inserts and flushes on one batch object, whilst another thread
 * accepts and processes populated batches into the other batch object.
 */
class TransferBatch extends Batch {

	private static final Logger logger = LoggerFactory.getLogger(TransferBatch.class);

	private final Exchanger<State> exchanger;

	protected TransferBatch(int batchCapacity, int slotCapacity, Exchanger<State> exchanger) {
		super(batchCapacity, slotCapacity);
		this.exchanger = exchanger;
	}

	@Override
	void flush() {
		try {
			var count = count();
			if (count > 0) {
				state = exchanger.exchange(state);
				reset();
			}
		} catch (InterruptedException exception) {
			logger.warn("Flush interrupted, batch transfer indefinitely delayed.", exception); // Flush was interrupted, but will be retried on any subsequent insertion or flushing operation.
			Thread.currentThread().interrupt();
		}
	}

	void accept(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
		state = exchanger.exchange(state, timeout, unit);
	}

	static Pair<TransferBatch, TransferBatch> createPair(int batchCapacity, int slotCapacity) {
		var exchanger = new Exchanger<State>();
		return new Pair<>(
			new TransferBatch(batchCapacity, slotCapacity, exchanger),
			new TransferBatch(batchCapacity, slotCapacity, exchanger)
		);
	}

}
