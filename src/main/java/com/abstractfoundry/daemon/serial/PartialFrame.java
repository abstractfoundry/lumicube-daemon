/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

public class PartialFrame extends Frame {

	@Override
	public int maximumLength() {
		return 251; // COBS Byte + Data + Sequence Byte + CRC16 Bytes + Delimiter Byte = 256 Bytes.
	}

}
