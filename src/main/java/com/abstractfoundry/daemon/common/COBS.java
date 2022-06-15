/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

/**
 * Incremental COBS encoder / decoder for buffers up to at most 256 bytes (including COBS overhead byte and NULL delimiter byte).
 */
public class COBS {

	public static int encode(final byte[] buffer, int pointer, final int offset, final int length) {
		if (buffer.length > 256) {
			throw new IllegalArgumentException("Illegal buffer length.");
		} else if (pointer < 0 || offset < 0 || length < 0) {
			throw new IllegalArgumentException("Illegal arguments.");
		} else if (offset + length > buffer.length) {
			throw new IllegalArgumentException("Inconsistent arguments.");
		}
		byte code = (byte) (offset - pointer);
		for (int index = offset; index < offset + length; index++) {
			final byte value = buffer[index];
			if (value == 0) {
				buffer[pointer] = code;
				pointer = index;
				code = 1;
			} else {
				if (code == -1) { // i.e. 0xFF
					throw new IllegalStateException("COBS overflow.");
				}
				code += 1;
			}
		}
		buffer[pointer] = code;
		return pointer;
	}

	public static int decode(final byte[] buffer, int counter, final int offset, final int length) {
		if (buffer.length > 256) {
			throw new IllegalArgumentException("Illegal buffer length.");
		} else if (counter < 0 || offset < 0 || length < 0) {
			throw new IllegalArgumentException("Illegal arguments.");
		} else if (offset + length > buffer.length) {
			throw new IllegalArgumentException("Inconsistent arguments.");
		}
		for (int index = offset; index < offset + length; index++) {
			final byte value = buffer[index];
			if (value == 0) {
				throw new IllegalStateException("NULL byte.");
			} else if (counter < 0) {
				throw new IllegalStateException("COBS underflow.");
			} else if (counter == 0) {
				buffer[index] = 0;
				counter = value & 0xFF; // Cast to unsigned integer.
			}
			counter--;
		}
		return counter;
	}

}
