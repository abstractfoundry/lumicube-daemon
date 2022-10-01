/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import com.lmax.disruptor.dsl.Disruptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch of bytes to publish to a disruptor.
 */
public class PublicationBatch<T> extends Batch {

	private static final Logger logger = LoggerFactory.getLogger(PublicationBatch.class);

	public static interface TransferFunction<T> {
		public abstract void accept(byte[] bytes, int length, T destination);
	}

	private final Disruptor<T> disruptor;
	private final TransferFunction<T> function;

	public PublicationBatch(int batchCapacity, int slotCapacity, Disruptor<T> disruptor, TransferFunction<T> function) {
		super(batchCapacity, slotCapacity);
		this.disruptor = disruptor;
		this.function = function;
	}

	@Override
	public void flush() {
		var count = count();
		if (count > 0) {
			var indices = new Integer[count]; // Array of slot indices to publish in batch. // TODO: Cache these arrays to reduce the number of allocations?
			for (int index = 0; index < count; index++) {
				indices[index] = index;
			}
			disruptor.publishEvents(this::transfer, indices);
			reset();
		}
	}

	private void transfer(T destination, long sequence, Integer index) {
		var slot = slot(index);
		function.accept(slot.bytes, slot.length, destination);
	}

}
