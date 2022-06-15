/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.serial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class Batch {

	private static final Logger logger = LoggerFactory.getLogger(Batch.class);

	protected static class Slot {

		protected final byte[] bytes;
		protected int length = 0;

		Slot(int size) {
			this.bytes = new byte[size];
		}

		void set(byte[] bytes, int offset, int length) {
			if (this.length != 0) {
				throw new IllegalStateException("Slot already set.");
			}
			System.arraycopy(bytes, offset, this.bytes, 0, length);
			this.length = length;
		}

		void reset() {
			length = 0;
		}

	}

	protected static class State { // Opaque abstraction of the batch state

		private Slot[] slots;
		private int cursor = 0;

	}

	protected State state = new State();

	protected Batch(int batchCapacity, int slotCapacity) {
		state.slots = new Slot[batchCapacity];
		for (int index = 0; index < state.slots.length; index++) {
			state.slots[index] = new Slot(slotCapacity);
		}
	}

	int capacity() {
		return state.slots.length;
	}

	int count() {
		return state.cursor;
	}

	Slot slot(int index) {
		if (index < 0 || index >= state.slots.length || index >= state.cursor) {
			throw new IllegalArgumentException("Invalid index.");
		}
		return state.slots[index];
	}

	void reset() {
		state.cursor = 0;
		for (int index = 0; index < state.slots.length; index++) {
			state.slots[index].reset();
		}
	}

	void insert(byte[] bytes, int offset, int length) {
		checkCursorAndFlushIfFull();
		if (state.cursor == state.slots.length) {
			throw new IllegalStateException("Batch overflow.");
		}
		state.slots[state.cursor].set(bytes, offset, length);
		state.cursor++;
		checkCursorAndFlushIfFull();
	}

	int read(int index, byte[] destination, int offset) {
		var slot = slot(index);
		var bytes = slot.bytes;
		var length = slot.length;
		System.arraycopy(bytes, 0, destination, offset, length);
		return length;
	}

	abstract void flush();

	private void checkCursor() {
		if (state.cursor < 0 || state.cursor > state.slots.length) {
			throw new IllegalStateException("Invalid cursor.");
		}
	}

	private void checkCursorAndFlushIfFull() {
		checkCursor();
		if (state.cursor == state.slots.length) {
			flush();
		}
	}

}