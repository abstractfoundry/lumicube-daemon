/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.ThreadSafe;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceTable {

	private static final Logger logger = LoggerFactory.getLogger(ServiceTable.class);

	static class KeyExhaustedException extends Exception {}
	static class KeyUnclaimedException extends Exception {}

	private static class Entry {
		public int occupations = -1;
		public final long[] timestamps = new long[32];
		public final AbstractContinuation[] continuations = new AbstractContinuation[32];
	}

	private final int size;
	private final Map<Integer, Entry> state;
	private final long expiry;

	ServiceTable(int size, int expiry) {
		this.size = size;
		this.state = new HashMap<>();
		this.expiry = (long) expiry * 1_000_000L;
	}

	@ThreadSafe
	synchronized int claim(int key, long timestamp, AbstractContinuation continuation) throws KeyExhaustedException {
		if (key < 0 || key >= size) {
			throw new IllegalArgumentException("Invalid key.");
		}
		var entry = state.computeIfAbsent(key, ignored -> new Entry());
		var id = Integer.numberOfLeadingZeros(entry.occupations);
		if (id == 32) {
			throw new KeyExhaustedException();
		}
		entry.occupations &= ~(1 << 31 - id);
		entry.timestamps[id] = timestamp;
		entry.continuations[id] = continuation;
		return id;
	}

	@ThreadSafe
	synchronized AbstractContinuation release(int key, int id) throws KeyUnclaimedException {
		if (key < 0 || key >= size) {
			throw new IllegalArgumentException("Invalid key.");
		} else if (id < 0 || id >= 32) {
			throw new IllegalArgumentException("Invalid ID.");
		}
		var entry = state.computeIfAbsent(key, ignored -> new Entry());
		var mask = 1 << 31 - id;
		if ((entry.occupations & mask) != 0) {
			throw new KeyUnclaimedException();
		}
		var continuation = entry.continuations[id];
		entry.occupations |= mask;
		entry.continuations[id] = null;
		entry.timestamps[id] = 0;
		return continuation;
	}

	@ThreadSafe
	synchronized int abortExpired(ExecutorService pool) {
		var accumulator = 0;
		var now = System.nanoTime();
		for (var entry : state.values()) {
			if (entry.occupations != -1) {
				for (var id = 0; id < 32; id++) {
					var mask = 1 << 31 - id;
					if ((entry.occupations & mask) == 0 && now - entry.timestamps[id] > expiry) {
						var continuation = entry.continuations[id];
						entry.occupations |= mask;
						entry.continuations[id] = null;
						entry.timestamps[id] = 0;
						try {
							Runnable task = continuation.timeout(); // Get the timeout task.
							if (task != null) {
								pool.submit(task); // Issue the task to the global pool, to avoid blocking this thread.
							}
						} catch (RuntimeException exception) {
							logger.warn("Error expiring continuation.", exception);
						}
						accumulator += 1;
					}
				}
			}
		}
		return accumulator;
	}

	@ThreadSafe
	synchronized int occupancyCount() {
		var accumulator = 0;
		for (var entry : state.values()) {
			accumulator += 32 - Integer.bitCount(entry.occupations);
		}
		return accumulator;
	}

}