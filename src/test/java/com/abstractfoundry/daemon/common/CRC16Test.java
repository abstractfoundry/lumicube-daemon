/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CRC16Test {

	@Test
	public void testEmptyBuffer() {
		byte[] buffer = {};
		int result = CRC16.calculate(buffer, 0, 0, 0xFFFF);
		assertEquals(0xFFFF, result);
	}

	@Test
	public void testZeroBuffer() {
		byte[] buffer = { 0x00 };
		int result = CRC16.calculate(buffer, 0, 1, 0xFFFF);
		assertEquals(0xE1F0, result);
	}

	@Test
	public void testOneBuffer() {
		byte[] buffer = { 0x01 };
		int result = CRC16.calculate(buffer, 0, 1, 0xFFFF);
		assertEquals(0xF1D1, result);
	}

	@Test
	public void testOneTwoBuffer() {
		byte[] buffer = { 0x01, 0x02 };
		int result = CRC16.calculate(buffer);
		assertEquals(0x0E7C, result);
	}

	@Test
	public void testZeroOneTwoThreeBuffer() {
		byte[] buffer = { 0x00, 0x01, 0x02, 0x03 };
		int result = CRC16.calculate(buffer);
		assertEquals(0xE5F1, result);
	}

	@Test
	public void testBufferSubset() {
		byte[] buffer = { 0x00, 0x01, 0x02, 0x03 };
		int result = CRC16.calculate(buffer, 1, 2);
		assertEquals(0x0E7C, result);
	}

}
