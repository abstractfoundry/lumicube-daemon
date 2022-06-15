/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.SimpleObjectPool;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PooledCountdownContinuation implements AbstractContinuation { // TODO: The allocations saved by pooling continuations is probably not worth the complexity (and therefore bugs).

	private static final Logger logger = LoggerFactory.getLogger(PooledCountdownContinuation.class);

	private final SimpleObjectPool<? extends PooledCountdownContinuation>.Slot slot;
	private final byte[] buffer = new byte[256];
	private final AtomicInteger countdown = new AtomicInteger(); // This continuation only executes when this countdown reaches zero.
	private final SlotReleasingTaskWrapper continuationTaskWrapper = new SlotReleasingTaskWrapper();
	private final SlotReleasingTaskWrapper timeoutTaskWrapper = new SlotReleasingTaskWrapper();

	public PooledCountdownContinuation(SimpleObjectPool<? extends PooledCountdownContinuation>.Slot slot) {
		this.slot = slot;
	}
	
	public final void reset() {
		countdown.set(0);
		continuationTaskWrapper.set(null);
		timeoutTaskWrapper.set(null);
	}

	public final void set(int countdownValue, Runnable continuationTask, Runnable timeoutTask) {
		var previous = countdown.getAndSet(countdownValue);
		if (previous != 0) {
			logger.warn("Setup continuation with non-zero countdown.");
		}
		continuationTaskWrapper.set(continuationTask);
		timeoutTaskWrapper.set(timeoutTask);
	}

	@Override
	public final Runnable next(byte[] buffer, int offset, int length) {
		System.arraycopy(buffer, offset, this.buffer, offset, length); // The provided buffer will be reused, so we must copy it locally.
		var value = countdown.decrementAndGet();
		if (value == 0) {
			return continuationTaskWrapper; // This is the ultimate invocation.
		} else {
			return null;
		}
	}

	@Override
	public final Runnable timeout() {
		var value = countdown.getAndSet(0);
		if (value > 0) {
			return timeoutTaskWrapper; // This continuation has not completed already, or timed-out.
		} else {
			return null;
		}
	}

	private class SlotReleasingTaskWrapper implements Runnable {

		private Runnable task;

		public void set(Runnable task) {
			this.task = task;
		}

		@Override
		public void run() {
			try {
				if (task != null) {
					task.run();
				}
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception whilst running continuation task.", exception);
			} finally {
				slot.release();
			}
		}

	}

}
