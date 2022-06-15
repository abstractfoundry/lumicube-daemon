/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.nio.ByteBuffer;

public class AsciiView implements CharSequence {

	private ByteBuffer buffer;
	private int offset;
	private int length;

	public void set(ByteBuffer buffer, int offset, int length) {
		this.buffer = buffer;
		this.offset = offset;
		this.length = length;
	}

	@Override
	public int length() {
		return length;
	}

	@Override
	public char charAt(int index) {
		if (buffer == null) {
			throw new IllegalStateException("Sequence uninitialised.");
		} else if (index < 0 || index >= length) {
			throw new IndexOutOfBoundsException("Invalid index.");
		}
		return (char) (buffer.get(offset + index) & 0xFF);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		if (buffer == null) {
			throw new IllegalStateException("Sequence uninitialised.");
		} else if (start < 0 || end < 0 || end > length || start > end) {
			throw new IndexOutOfBoundsException("Invalid indices.");
		}
		var result = new AsciiView();
		result.set(buffer, offset + start, end - start);
		return result;
	}

	@Override
	public String toString() {
		var builder = new StringBuilder();
		builder.append(this);
		return builder.toString();
	}

}
