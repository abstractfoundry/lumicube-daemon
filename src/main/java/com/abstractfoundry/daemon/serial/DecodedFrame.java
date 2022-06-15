/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

public class DecodedFrame extends Frame {

	@Override
	public int maximumLength() {
		return 255; // Excludes Delimiter Byte.
	}

}
