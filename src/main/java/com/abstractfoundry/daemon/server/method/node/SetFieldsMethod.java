/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server.method.node;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.FlatDictionary;
import com.abstractfoundry.daemon.bus.Namespace;
import com.abstractfoundry.daemon.common.JsonToken;
import com.abstractfoundry.daemon.common.SimpleObjectPool;
import com.abstractfoundry.daemon.server.MethodJsonParser;
import com.abstractfoundry.daemon.uavcan.AbstractContinuation;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.Node;
import com.abstractfoundry.daemon.uavcan.NullContinuation;
import com.abstractfoundry.daemon.uavcan.PooledCountdownContinuation;
import com.abstractfoundry.daemon.uavcan.TypeId;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetFieldsMethod { // TODO: Abstract common logic with DisplaySetMethod.

	private static final Logger logger = LoggerFactory.getLogger(SetFieldsMethod.class);

	private static final int MAX_KEYS = 320;
	private static final int BATCH_CAPACITY = 32; // TODO: We should work out the batch capacity dynamically, but for now we should be able to fit 32 fields into a batch (assuming they are up to 4 bytes, so at most 128 bytes of payload).
	private static final int BUFFER_COUNT = MAX_KEYS / BATCH_CAPACITY;

	private static final AbstractContinuation NULL_CONTINUATION = new NullContinuation();

	private final Node daemonNode;
	private final Store store;
	private final SimpleObjectPool<PooledCountdownContinuation> continuationPool;

	private final int[] ordering = new int[MAX_KEYS];
	private final int[] orderedFieldKeys = new int[MAX_KEYS];
	private final int[] orderedFieldValues = new int[MAX_KEYS];

	private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];

	private Namespace.Module module; // The module context within which to parse and issue the request.
	private final Parser parser = new Parser();

	public SetFieldsMethod(Node daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
		this.continuationPool = new SimpleObjectPool<>(64,
			slot -> new PooledCountdownContinuation(slot)
		);
		for (var index = 0; index < BUFFER_COUNT; index++) {
			buffers[index] = ByteBuffer.allocate(256);
			buffers[index].order(ByteOrder.LITTLE_ENDIAN);
		}
	}

	public void parse(String moduleName, CharSequence data) {
		module = store.getNamespace().getModule(moduleName);
		parser.parse(data);
	}

	private final IntComparator comparator = (int left, int right) ->
		parser.keys[left] - parser.keys[right];

	public boolean invoke(Runnable continuationTask, Runnable timeoutTask) {
		// (1) Order the colour values and append to the field arrays.
		var count = parser.cursor;
		for (var index = 0; index < count; index++) {
			ordering[index] = index;
		}
		IntArrays.quickSort(ordering, 0, count, comparator);
		for (var index = 0; index < count; index++) {
			var position = ordering[index];
			orderedFieldKeys[index] = parser.keys[position];
			orderedFieldValues[index] = parser.values[position];
		}
		var asynchronous = parser.asynchronous;
		// (1) Setup the continuation (if not asynchronous).
		SimpleObjectPool<PooledCountdownContinuation>.Slot continuationSlot = null;
		AbstractContinuation continuation = null;
		if (!asynchronous) {
			try {
				continuationSlot = continuationPool.claim();
			} catch (SimpleObjectPool.PoolExhaustedException exception) {
				return false; // Busy, retry later.
			}
			var instance = continuationSlot.instance;
			instance.set(1, continuationTask, timeoutTask);
			continuation = instance;
		}
		// (2) Serialise the batches.
		final var batchCount = count / BATCH_CAPACITY + (count % BATCH_CAPACITY != 0 ? 1 : 0);
		for (var batchNumber = 0; batchNumber < batchCount; batchNumber++) {
			final var batchStart = batchNumber * BATCH_CAPACITY;
			final var batchEnd = Integer.min(batchStart + BATCH_CAPACITY, count);
			final var length = FlatDictionary.serialise(buffers[batchNumber].array(), 0, orderedFieldKeys, orderedFieldValues, batchStart, batchEnd - batchStart, module.getMetadata());
			buffers[batchNumber].limit(length);
			buffers[batchNumber].position(0);
		}
		// (3) Make the request.
		final int requestPriority = 20; // TODO: Make configurable.
		try {
			if (!asynchronous) {
				daemonNode.request(module.getId(), TypeId.SET_FIELDS, requestPriority, buffers, batchCount, continuation);
			} else { // Reply before getting the reponse from the module.
				daemonNode.request(module.getId(), TypeId.SET_FIELDS, requestPriority, buffers, batchCount, NULL_CONTINUATION);
				continuationTask.run();
			}
		} catch (BackoffException exception) {
			if (continuationSlot != null) {
				continuationSlot.instance.reset();
				continuationSlot.release();
			}
			return false; // Busy, retry later.
		}
		return true; // Complete.
	}

	private class Parser extends MethodJsonParser {

		private final StringBuilder buffer = new StringBuilder();

		public int cursor;
		public int[] keys = new int[MAX_KEYS];
		public int[] values = new int[MAX_KEYS];
		public boolean asynchronous;

		@Override
		protected void clear() {
			cursor = 0;
			asynchronous = false;
		}

		@Override
		protected void argument(int index) {
			if (index == 0) {
				values();
			} else {
				throw new RuntimeException("Unexpected argument: '" + index + "'.");
			}
		}

		@Override
		protected void argument(CharSequence name) {
			if (CharSequence.compare(name, "asynchronous") == 0) {
				asynchronous();
			} else {
				throw new RuntimeException("Unexpected argument: '" + name + "'.");
			}
		}

		private void values() {
			expect(JsonToken.START_OBJECT);
			while (!accept(JsonToken.END_OBJECT)) {
				// Read the field name.
				buffer.setLength(0);
				read(buffer);
				consume();
				var fieldName = buffer.toString();
				keys[cursor] = module.getKey(fieldName); // TODO: Support suffices due to spanning fields.
				 // Read the field value.
				if (is(JsonToken.VALUE_STRING)) {
					buffer.setLength(0);
					read(buffer);
					consume();
					values[cursor] = Integer.parseInt(buffer, 0, buffer.length(), 10); // TODO: Support other kinds of value.
				} else {
					values[cursor] = (int) number(); // TODO: Support other kinds of value.
					consume();
				}
				cursor++;
			}
		}

		private void asynchronous() {
			if (accept(JsonToken.VALUE_TRUE)) {
				asynchronous = true;
			} else if (accept(JsonToken.VALUE_FALSE)) {
				asynchronous = false;
			} else {
				throw new RuntimeException("Expected boolean.");
			}
		}
	}

}
