/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server.method.node.display;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.FlatDictionary;
import com.abstractfoundry.daemon.bus.Metadata;
import com.abstractfoundry.daemon.common.JsonToken;
import com.abstractfoundry.daemon.common.SimpleObjectPool;
import com.abstractfoundry.daemon.server.MethodJsonParser;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.Node;
import com.abstractfoundry.daemon.uavcan.PooledCountdownContinuation;
import com.abstractfoundry.daemon.uavcan.TypeId;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DisplaySetMethod { // TODO: Abstract common logic with SetFieldsMethod.

	private static final Logger logger = LoggerFactory.getLogger(DisplaySetMethod.class);

	private static final int MAX_KEYS = 640;
	private static final int BATCH_CAPACITY = 64;
	private static final int BUFFER_COUNT = MAX_KEYS / BATCH_CAPACITY;
	
	private final Node daemonNode;
	private final Store store;
	private final SimpleObjectPool<PooledCountdownContinuation> continuationPool;

	private final int[] colourOrdering = new int[MAX_KEYS];
	private final int[] orderedFieldKeys = new int[MAX_KEYS];
	private final int[] orderedFieldValues = new int[MAX_KEYS];

	private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];
	
	private int cachedDestinationId;
	private Metadata cachedMetadata;
	private int cachedLedsKey = -1;
	private int cachedShowKey = -1;
	private boolean available = false;

	private final Parser parser = new Parser();

	public DisplaySetMethod(Node daemonNode, Store store) {
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

	public void parse(CharSequence data) {
		parser.parse(data);
	}

	private final IntComparator colourKeysComparator = (int left, int right) ->
		parser.colourKeys[left] - parser.colourKeys[right];

	public boolean invoke(Runnable continuationTask, Runnable timeoutTask) {
		if (!available) {
			var module = store.getNamespace().getModule("display");
			if (module != null) {
				cachedDestinationId = module.getId();
				cachedMetadata = module.getMetadata();
				cachedLedsKey = module.getKey("led_colour");
				cachedShowKey = module.getKey("show");
				available = true;
			}
		}
		if (!available) {
			throw new IllegalStateException("Destination unavailable.");
		}
		var fieldCount = 0;
		// (1) Order the colour values and append to the field arrays.
		var colourCount = parser.colourCursor;
		for (var index = 0; index < colourCount; index++) {
			colourOrdering[index] = index;
		}
		IntArrays.quickSort(colourOrdering, 0, colourCount, colourKeysComparator);
		for (var index = 0; index < colourCount; index++) {
			var orderingIndex = colourOrdering[index];
			orderedFieldKeys[index] = parser.colourKeys[orderingIndex] + cachedLedsKey;
			orderedFieldValues[index] = parser.colourValues[orderingIndex];
		}
		fieldCount += colourCount;
		// (2) Conditionally append the show field.
		if (parser.show) {
			orderedFieldKeys[fieldCount] = cachedShowKey;
			orderedFieldValues[fieldCount] = 1;
			fieldCount += 1;
		}
		// (3) Setup the continuation.
		final var batchCount = fieldCount / BATCH_CAPACITY + (fieldCount % BATCH_CAPACITY != 0 ? 1 : 0);
		SimpleObjectPool<PooledCountdownContinuation>.Slot continuationSlot;
		try {
			continuationSlot = continuationPool.claim();
		} catch (SimpleObjectPool.PoolExhaustedException exception) {
			return false; // Busy, retry later.
		}
		final var continuation = continuationSlot.instance;
		continuation.set(batchCount, continuationTask, timeoutTask);
		// (4) Serialise the batches.
		for (var batchNumber = 0; batchNumber < batchCount; batchNumber++) {
			final var batchStart = batchNumber * BATCH_CAPACITY;
			final var batchEnd = Integer.min(batchStart + BATCH_CAPACITY, fieldCount);
			final var length = FlatDictionary.serialise(buffers[batchNumber].array(), 0, orderedFieldKeys, orderedFieldValues, batchStart, batchEnd - batchStart, cachedMetadata);
			buffers[batchNumber].limit(length);
			buffers[batchNumber].position(0);
		}
		// (5) Make the request.
		final int requestPriority = 20; // TODO: Make configurable.
		try {
			daemonNode.request(cachedDestinationId, TypeId.SET_FIELDS, requestPriority, buffers, batchCount, continuation);
		} catch (BackoffException exception) {
			continuationSlot.instance.reset();
			continuationSlot.release();
			return false; // Busy, retry later.
		}
		return true; // Complete.
	}

	private class Parser extends MethodJsonParser {

		private final StringBuilder buffer = new StringBuilder();

		public int colourCursor;
		public int[] colourKeys = new int[MAX_KEYS];
		public int[] colourValues = new int[MAX_KEYS];
		public boolean show;

		@Override
		protected void clear() {
			colourCursor = 0;
			show = false;
		}

		@Override
		protected void argument(int index) {
			if (index == 0) {
				colours();
			} else {
				throw new RuntimeException("Unexpected argument: '" + index + "'.");
			}
		}

		@Override
		protected void argument(CharSequence name) {
			if (CharSequence.compare(name, "show") == 0) {
				show();
			} else {
				throw new RuntimeException("Unexpected argument: '" + name + "'.");
			}
		}

		private void colours() {
			expect(JsonToken.START_OBJECT);
			while (!accept(JsonToken.END_OBJECT)) {
				buffer.setLength(0);
				read(buffer); // The LED index encoded as a string.
				consume();
				colourKeys[colourCursor] = Integer.parseInt(buffer, 0, buffer.length(), 10);
				colourValues[colourCursor] = (int) number();
				colourCursor++;
				consume();
			}
		}

		private void show() {
			if (accept(JsonToken.VALUE_TRUE)) {
				show = true;
			} else if (accept(JsonToken.VALUE_FALSE)) {
				show = false;
			} else {
				throw new RuntimeException("Expected boolean.");
			}
		}

	}

}
