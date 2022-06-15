/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.common.ThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface TransmissionFunction {

	@ThreadSafe
	public void transmit(ByteBuffer buffer) throws IOException;

}
