/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.util.UUID;

public class Builder {

	public static UUID uuid(byte[] buffer, int offset, int length) {
		if (buffer == null || offset < 0 || length != 16 || offset + length > buffer.length) {
			throw new IllegalArgumentException("Invalid arguments.");
		}
		long high = 0, low = 0;
		for (int index = offset; index < offset + 8; index++) {
			high <<= 8;
			high |= buffer[index] & 0xFF;
		}
		for (int index = offset + 8; index < offset + 16; index++) {
			low <<= 8;
			low |= buffer[index] & 0xFF;
		}
		return new UUID(high, low);
	}

}
