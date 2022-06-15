/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

public abstract class Frame {

	private final byte[] bytes = new byte[256];
	private int length = 0;

	public void write(byte value) {
		if (maximumLength() < this.length + 1) {
			reset();
			throw new IllegalArgumentException("Maximum length exceeded.");
		}
		this.bytes[this.length] = value;
		this.length += 1;
	}

	public void write(byte[] bytes, int offset, int length) {
		if (maximumLength() < this.length + length) {
			reset();
			throw new IllegalArgumentException("Maximum length exceeded.");
		}
		System.arraycopy(bytes, offset, this.bytes, this.length, length);
		this.length += length;
	}

	public abstract int maximumLength();

	public byte[] getBytes() {
		return bytes;
	}

	public int getLength() {
		return length;
	}

	void reset() {
		this.length = 0;
	}

}