/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class COBSTest {

	@Test
	public void testEmptyBuffer() {
		byte[] buffer = { 0 };
		int pointer = COBS.encode(buffer, 0, 1, 0);
		assertEquals(0, pointer);
		assertEquals(1, buffer[0]);
		int counter = COBS.decode(buffer, 0, 0, 1);
		assertEquals(0, counter);
		assertEquals(0, buffer[0]);
	}

	@Test
	public void testZeroBuffer() {
		byte[] buffer = { 0, 0 };
		int pointer = COBS.encode(buffer, 0, 1, 1);
		assertEquals(1, pointer);
		assertEquals(1, buffer[0]);
		assertEquals(1, buffer[1]);
		int counter = COBS.decode(buffer, 0, 0, 2);
		assertEquals(0, counter);
		assertEquals(0, buffer[0]);
		assertEquals(0, buffer[1]);
	}

	@Test
	public void testOneBuffer() {
		byte[] buffer = { 0, 1 };
		int pointer = COBS.encode(buffer, 0, 1, 1);
		assertEquals(0, pointer);
		assertEquals(2, buffer[0]);
		assertEquals(1, buffer[1]);
		int counter = COBS.decode(buffer, 0, 0, 2);
		assertEquals(0, counter);
		assertEquals(0, buffer[0]);
		assertEquals(1, buffer[1]);
	}

	@Test
	public void testZeroOneZeroTwoBuffer() {
		byte[] buffer = { 0, 0, 1, 0, 2 };
		int pointer = COBS.encode(buffer, 0, 1, 4);
		assertEquals(3, pointer);
		assertEquals(1, buffer[0]);
		assertEquals(2, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(2, buffer[3]);
		assertEquals(2, buffer[4]);
		int counter = COBS.decode(buffer, 0, 0, 5);
		assertEquals(0, counter);
		assertEquals(0, buffer[0]);
		assertEquals(0, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(0, buffer[3]);
		assertEquals(2, buffer[4]);
	}

	@Test
	public void testBufferInChunks() {
		byte[] buffer = { 0, 0, 1, 0, 2 };
		int pointer1 = COBS.encode(buffer, 0, 1, 2);
		assertEquals(1, pointer1);
		assertEquals(1, buffer[0]);
		assertEquals(2, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(0, buffer[3]);
		assertEquals(2, buffer[4]);
		int pointer2 = COBS.encode(buffer, pointer1, 3, 2);
		assertEquals(3, pointer2);
		assertEquals(1, buffer[0]);
		assertEquals(2, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(2, buffer[3]);
		assertEquals(2, buffer[4]);
		int counter1 = COBS.decode(buffer, 0, 0, 2);
		assertEquals(1, counter1);
		assertEquals(0, buffer[0]);
		assertEquals(0, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(2, buffer[3]);
		assertEquals(2, buffer[4]);
		int counter2 = COBS.decode(buffer, counter1, 2, 3);
		assertEquals(0, counter2);
		assertEquals(0, buffer[0]);
		assertEquals(0, buffer[1]);
		assertEquals(1, buffer[2]);
		assertEquals(0, buffer[3]);
		assertEquals(2, buffer[4]);
	}

	@Test
	public void testMaximumLengthBuffer() {
		byte[] buffer = new byte[255];
		for (int index = 0; index < 255; index++) {
			buffer[index] = (byte) index;
		}
		int pointer = COBS.encode(buffer, 0, 1, 254);
		assertEquals(0, pointer);
		assertEquals(-1, buffer[0]); // i.e. 0xFF
		for (int index = 1; index < 255; index++) {
			assertEquals((byte) index, buffer[index]);
		}
		int counter = COBS.decode(buffer, 0, 0, 255);
		assertEquals(0, counter);
		assertEquals(0, buffer[0]);
		for (int index = 1; index < 255; index++) {
			assertEquals((byte) index, buffer[index]);
		}
	}

}
