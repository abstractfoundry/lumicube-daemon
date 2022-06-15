/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CallbackContinuation implements AbstractContinuation {

		private static final Logger logger = LoggerFactory.getLogger(CallbackContinuation.class);

	private final byte[] buffer = new byte[256];

	public static interface Callback {
		void invoke(boolean timeout, byte[] buffer, int offset, int length);
	}

	private final Callback callback;
	
	public CallbackContinuation(Callback callback) {
		this.callback = callback;
	}

	@Override
	public Runnable next(byte[] buffer, int offset, int length) {
		System.arraycopy(buffer, offset, this.buffer, offset, length); // The provided buffer will be reused, so we must copy it locally.
		return () -> {
			try {
				callback.invoke(false, this.buffer, offset, length);
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception in callback.", exception);
			}
		};
	}

	@Override
	public Runnable timeout() {
		return () -> callback.invoke(true, null, 0, 0);
	}
	
}
