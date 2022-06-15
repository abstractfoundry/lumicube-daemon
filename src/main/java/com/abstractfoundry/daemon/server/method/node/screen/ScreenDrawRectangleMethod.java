/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server.method.node.screen;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.FlatDictionary;
import com.abstractfoundry.daemon.bus.Metadata;
import com.abstractfoundry.daemon.common.SimpleObjectPool;
import com.abstractfoundry.daemon.server.MethodJsonParser;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.Node;
import com.abstractfoundry.daemon.uavcan.PooledCountdownContinuation;
import com.abstractfoundry.daemon.uavcan.TypeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenDrawRectangleMethod { // TODO: Abstract common logic with SetFieldsMethod.

	private static final Logger logger = LoggerFactory.getLogger(ScreenDrawRectangleMethod.class);

	private final Node daemonNode;
	private final Store store;
	private final SimpleObjectPool<PooledCountdownContinuation> continuationPool;

	private final byte[] buffer = new byte[256];

	private final int[] batchKeys = new int[6]; // x, y, width, height, colour, draw.
	private final int[] batchValues = new int[6];

	private int cachedDestinationId;
	private Metadata cachedMetadata;
	private int cachedRectangleXKey = -1;
	private int cachedRectangleYKey = -1;
	private int cachedRectangleWidthKey = -1;
	private int cachedRectangleHeightKey = -1;
	private int cachedRectangleColourKey = -1;
	private int cachedRectangleDrawKey = -1;
	private boolean available = false;

	private final Parser parser = new Parser();

	public ScreenDrawRectangleMethod(Node daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
		this.continuationPool = new SimpleObjectPool<>(64,
			slot -> new PooledCountdownContinuation(slot)
		);
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
				cachedRectangleXKey = module.getKey("rectangle_x");
				cachedRectangleYKey = module.getKey("rectangle_y");
				cachedRectangleWidthKey = module.getKey("rectangle_width");
				cachedRectangleHeightKey = module.getKey("rectangle_height");
				cachedRectangleColourKey = module.getKey("rectangle_colour");
				cachedRectangleDrawKey = module.getKey("rectangle_draw");
				available = true;
			}
		}
		if (!available) {
			throw new IllegalStateException("Destination unavailable.");
		}
		var x = parser.x;
		var y = parser.y;
		var width = parser.width;
		var height = parser.height;
		var colour = parser.colour;
		// (1) Setup the continuation.
		SimpleObjectPool<PooledCountdownContinuation>.Slot continuationSlot;
		try {
			continuationSlot = continuationPool.claim();
		} catch (SimpleObjectPool.PoolExhaustedException exception) {
			return false; // Busy, retry later.
		}
		final var continuation = continuationSlot.instance;
		continuation.set(1, continuationTask, timeoutTask);
		// (2) Serialise the batch.
		batchKeys[0] = cachedRectangleXKey;
		batchValues[0] = x;
		batchKeys[1] = cachedRectangleYKey;
		batchValues[1] = y;
		batchKeys[2] = cachedRectangleWidthKey;
		batchValues[2] = width;
		batchKeys[3] = cachedRectangleHeightKey;
		batchValues[3] = height;
		batchKeys[4] = cachedRectangleColourKey;
		batchValues[4] = ((colour >> 19) & 0x1F) << 11 | ((colour >> 10) & 0x3F) << 5 | ((colour >> 3) & 0x1F); // 24-bit colour to 16-bit colour conversion.
		batchKeys[5] = cachedRectangleDrawKey;
		batchValues[5] = 1;
		final var length = FlatDictionary.serialise(buffer, 0, batchKeys, batchValues, 0, 6, cachedMetadata);
		// (3) Make the request.
		final int requestPriority = 20; // TODO: Make configurable.
		try {
			daemonNode.request(cachedDestinationId, TypeId.SET_FIELDS, requestPriority, buffer, 0, length, continuation);
		} catch (BackoffException exception) {
			continuationSlot.instance.reset();
			continuationSlot.release();
			return false; // Busy, retry later.
		}
		return true; // Complete.
	}

	private class Parser extends MethodJsonParser {

		public int x, y, width, height, colour;

		@Override
		protected void clear() {
			x = 0;
			y = 0;
			width = 0;
			height = 0;
			colour = 0;
		}

		@Override
		protected void argument(int index) {
			if (index == 0) {
				x();
			} else if (index == 1) {
				y();
			} else if (index == 2) {
				width();
			} else if (index == 3) {
				height();
			} else if (index == 4) {
				colour();
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

		private void width() {
			width = (int) number();
			consume();
		}

		private void height() {
			height = (int) number();
			consume();
		}

		private void colour() {
			colour = (int) number();
			consume();
		}

	}

}
