/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.ThreadSafe;

class BroadcastTable {

	private final byte[] state;

	BroadcastTable(int size) {
		state = new byte[size];
		for (var key = 0; key < state.length; key++) {
			state[key] = 0;
		}
	}

	@ThreadSafe
	synchronized int next(int key) {
		if (key < 0 || key >= state.length) {
			throw new IllegalArgumentException("Invalid key.");
		}
		var id = state[key];
		var update = (id + 1) & 0x1F;
		state[key] = (byte) update;
		return id;
	}

}
