/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.uavcan;

public interface AbstractContinuation {

	public Runnable next(byte[] buffer, int offset, int length); // On each invocation, this method either returns some runnable task, or null.
	public Runnable timeout(); // On each invocation, this method either returns some runnable task, or null.

}
