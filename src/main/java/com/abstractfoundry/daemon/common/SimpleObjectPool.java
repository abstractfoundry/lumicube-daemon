/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.lang.reflect.Array;
import java.util.function.Function;

public class SimpleObjectPool<T> { // TODO: Better to use a free list?

	public static class PoolExhaustedException extends Exception {}
	public static class SlotUnclaimedException extends Exception {}

	public class Slot {

		private final int key;
		public T instance;

		private Slot(int key) {
			this.key = key;
		}

		private void set(T instance) {
			if (this.instance != null || instance == null) {
				throw new IllegalStateException("Instance already set, or set to null.");
			}
			this.instance = instance;
		}

		public void release() {
			try {
				replace(key);
			} catch (SlotUnclaimedException exception) {
				throw new IllegalStateException("Attempt to release unclaimed slot.");
			}
		}

	}

	private final int[] state;
	private final Slot[] slots;
	private final Function<Slot, T> constructor;

	public SimpleObjectPool(int size, Function<Slot, T> constructor) {
		if (size % 32 != 0) {
			throw new IllegalArgumentException("Expected multiple of 32.");
		}
		state = new int[size / 32];
		for (var row = 0; row < state.length; row++) {
			state[row] = -1;
		}
		slots = (Slot[]) Array.newInstance(Slot.class, size);
		this.constructor = constructor;
	}

	@ThreadSafe
	public synchronized Slot claim() throws PoolExhaustedException {
		for (var row = 0; row < state.length; row++) {
			var word = state[row];
			var bits = Integer.numberOfLeadingZeros(word);
			if (bits == 32) {
				continue;
			}
			state[row] = word & ~(1 << 31 - bits);
			var key = (row << 5) | bits;
			if (slots[key] == null) {
				var slot = new Slot(key);
				var instance = constructor.apply(slot);
				slot.set(instance);
				slots[key] = slot;
			}
			return slots[key];
		}
		throw new PoolExhaustedException();
	}

	@ThreadSafe
	private synchronized void replace(int key) throws SlotUnclaimedException {
		if (key < 0 || key >= slots.length) {
			throw new IllegalArgumentException("Invalid key.");
		}
		var row = key / 32;
		var bits = key % 32;
		var word = state[row];
		var mask = 1 << 31 - bits;
		if ((word & mask) != 0) {
			throw new SlotUnclaimedException();
		}
		state[row] = word | mask;
	}

}
