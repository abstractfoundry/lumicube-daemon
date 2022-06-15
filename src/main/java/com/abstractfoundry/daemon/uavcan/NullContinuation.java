/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

public class NullContinuation implements AbstractContinuation {

	@Override
	public Runnable next(byte[] buffer, int offset, int length) {
		return null;
	}

	@Override
	public Runnable timeout() {
		return null;
	}

}
