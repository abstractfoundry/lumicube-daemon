/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

public class EncodedFrame extends Frame {

	@Override
	public int maximumLength() {
		return 256; // Includes Delimiter Byte.
	}

}
