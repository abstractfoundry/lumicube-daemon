/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server.method.node.screen;

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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenSetHalfRowMethod { // TODO: Abstract common logic with SetFieldsMethod.

	private static final Logger logger = LoggerFactory.getLogger(ScreenSetHalfRowMethod.class);

	private static final int MAX_PIXELS = 160; // Set at most half the width of the screen at once.
	private static final int BATCH_CAPACITY = 80;
	private static final int BUFFER_COUNT = MAX_PIXELS / BATCH_CAPACITY;

	private final Node daemonNode;
	private final Store store;
	private final SimpleObjectPool<PooledCountdownContinuation> continuationPool;

	private final int[] batchKeys = new int[MAX_PIXELS + 6]; // Pixels plus metadata (x, y, width, height, start & stop fields).
	private final int[] batchValues = new int[MAX_PIXELS + 6];

	private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];

	private int cachedDestinationId;
	private Metadata cachedMetadata;
	private int cachedPixelWindowXKey = -1;
	private int cachedPixelWindowYKey = -1;
	private int cachedPixelWindowWidthKey = -1;
	private int cachedPixelWindowHeightKey = -1;
	private int cachedStartPixelStreamingKey = -1;
	private int cachedPixelDataStreamKey = -1;
	private int cachedStopPixelStreamingKey = -1;
	private boolean available = false;

	private final Parser parser = new Parser();

	public ScreenSetHalfRowMethod(Node daemonNode, Store store) {
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

	public boolean invoke(Runnable continuationTask, Runnable timeoutTask) {
		if (!available) {
			var module = store.getNamespace().getModule("screen");
			if (module != null) {
				cachedDestinationId = module.getId();
				cachedMetadata = module.getMetadata();
				cachedPixelWindowXKey = module.getKey("pixel_window_x");
				cachedPixelWindowYKey = module.getKey("pixel_window_y");
				cachedPixelWindowWidthKey = module.getKey("pixel_window_width");
				cachedPixelWindowHeightKey = module.getKey("pixel_window_height");
				cachedStartPixelStreamingKey = module.getKey("start_pixel_streaming");
				cachedPixelDataStreamKey = module.getKey("pixel_data_stream");
				cachedStopPixelStreamingKey = module.getKey("stop_pixel_streaming");
				available = true;
			}
		}
		if (!available) {
			throw new IllegalStateException("Destination unavailable.");
		}
		var x = parser.x;
		var y = parser.y;
		var pixelCount = parser.pixelCursor;
		// (1) Setup the continuation.
		final var batchCount = pixelCount / BATCH_CAPACITY + (pixelCount % BATCH_CAPACITY != 0 ? 1 : 0);
		SimpleObjectPool<PooledCountdownContinuation>.Slot continuationSlot;
		try {
			continuationSlot = continuationPool.claim();
		} catch (SimpleObjectPool.PoolExhaustedException exception) {
			return false; // Busy, retry later.
		}
		final var continuation = continuationSlot.instance;
		continuation.set(batchCount, continuationTask, timeoutTask);
		// (2) Serialise the batches.
		for (var batchNumber = 0; batchNumber < batchCount; batchNumber++) {
			final var batchStart = batchNumber * BATCH_CAPACITY;
			final var batchEnd = Integer.min(batchStart + BATCH_CAPACITY, pixelCount);
			final var batchSize = batchEnd - batchStart;
			batchKeys[0] = cachedPixelWindowXKey;
			batchValues[0] = x + batchStart;
			batchKeys[1] = cachedPixelWindowYKey;
			batchValues[1] = y;
			batchKeys[2] = cachedPixelWindowWidthKey;
			batchValues[2] = batchSize;
			batchKeys[3] = cachedPixelWindowHeightKey;
			batchValues[3] = 1;
			batchKeys[4] = cachedStartPixelStreamingKey;
			batchValues[4] = 1;
			for (var offset = 0; offset < batchSize; offset++) {
				batchKeys[5 + offset] = cachedPixelDataStreamKey + offset;
				var pixel = parser.pixels[batchStart + offset];
				var colour = ((pixel >> 19) & 0x1F) << 11 | ((pixel >> 10) & 0x3F) << 5 | ((pixel >> 3) & 0x1F); // 24-bit colour to 16-bit colour conversion.
				batchValues[5 + offset] = colour;
			}
			batchKeys[5 + batchSize] = cachedStopPixelStreamingKey;
			batchValues[5 + batchSize] = 1;
			final var length = FlatDictionary.serialise(buffers[batchNumber].array(), 0, batchKeys, batchValues, 0, 6 + batchSize, cachedMetadata);
			buffers[batchNumber].limit(length);
			buffers[batchNumber].position(0);
		}
		// (3) Make the request.
		final int requestPriority = 20; // TODO: Make configurable.
		try {
			LockSupport.parkNanos(8_000_000L); // Wait at least 8 milliseconds for any previous screen data to be processed.
			daemonNode.request(cachedDestinationId, TypeId.SET_FIELDS, requestPriority, buffers, batchCount, continuation);
		} catch (BackoffException exception) {
			continuationSlot.instance.reset();
			continuationSlot.release();
			return false; // Busy, retry later.
		}
		return true; // Complete.
	}

	private class Parser extends MethodJsonParser {

		public int x, y;
		public int pixelCursor;
		public int[] pixels = new int[MAX_PIXELS];

		@Override
		protected void clear() {
			x = 0;
			y = 0;
			pixelCursor = 0;
		}

		@Override
		protected void argument(int index) {
			if (index == 0) {
				x();
			} else if (index == 1) {
				y();
			} else if (index == 2) {
				pixels();
			} else {
				throw new RuntimeException("Unexpected argument: '" + index + "'.");
			}
		}

		@Override
		protected void argument(CharSequence name) {
			throw new RuntimeException("Unexpected argument: '" + name + "'.");
		}

		private void x() {
			x = (int) number();
			consume();
		}

		private void y() {
			y = (int) number();
			consume();
		}

		private void pixels() {
			expect(JsonToken.START_ARRAY);
			while (!accept(JsonToken.END_ARRAY)) {
				pixels[pixelCursor] = (int) number();
				pixelCursor++;
				consume();
			}
		}

	}


}
