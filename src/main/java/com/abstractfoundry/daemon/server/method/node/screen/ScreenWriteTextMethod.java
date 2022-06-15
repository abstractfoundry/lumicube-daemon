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

public class ScreenWriteTextMethod { // TODO: Abstract common logic with SetFieldsMethod.

	private static final Logger logger = LoggerFactory.getLogger(ScreenWriteTextMethod.class);

	private final int MAX_CHARACTERS = 128;

	private final Node daemonNode;
	private final Store store;
	private final SimpleObjectPool<PooledCountdownContinuation> continuationPool;

	private final byte[] buffer = new byte[256];

	private final int[] batchKeys = new int[MAX_CHARACTERS + 6]; // text, x, y, size, colour, background_colour, draw.
	private final int[] batchValues = new int[MAX_CHARACTERS + 6];

	private int cachedDestinationId;
	private Metadata cachedMetadata;
	private int cachedTextKey = -1;
	private int cachedTextXKey = -1;
	private int cachedTextYKey = -1;
	private int cachedTextSizeKey = -1;
	private int cachedTextColourKey = -1;
	private int cachedTextBackgroundColourKey = -1;
	private int cachedTextDrawKey = -1;
	private boolean available = false;

	private final Parser parser = new Parser();

	public ScreenWriteTextMethod(Node daemonNode, Store store) {
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
				cachedTextKey = module.getKey("text");
				cachedTextXKey = module.getKey("text_x");
				cachedTextYKey = module.getKey("text_y");
				cachedTextSizeKey = module.getKey("text_size");
				cachedTextColourKey = module.getKey("text_colour");
				cachedTextBackgroundColourKey = module.getKey("text_background_colour");
				cachedTextDrawKey = module.getKey("text_draw");
				available = true;
			}
		}
		if (!available) {
			throw new IllegalStateException("Destination unavailable.");
		}
		var text = parser.text;
		var x = parser.x;
		var y = parser.y;
		var size = parser.size;
		var colour = parser.colour;
		var background_colour = parser.background_colour;
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
		final var count = Math.min(text.length(), MAX_CHARACTERS - 1); // Reserve space for NULL byte (string terminator).
		for (var offset = 0; offset < count; offset++) {
			batchKeys[offset] = cachedTextKey + offset;
			batchValues[offset] = text.charAt(offset); // TODO: Do we need to zero out the rest of the firmware's buffer?
		}
		batchKeys[count + 0] = cachedTextKey + count;
		batchValues[count + 0] = 0; // NULL byte (string terminator).
		batchKeys[count + 1] = cachedTextXKey;
		batchValues[count + 1] = x;
		batchKeys[count + 2] = cachedTextYKey;
		batchValues[count + 2] = y;
		batchKeys[count + 3] = cachedTextSizeKey;
		batchValues[count + 3] = size;
		batchKeys[count + 4] = cachedTextColourKey;
		batchValues[count + 4] = ((colour >> 19) & 0x1F) << 11 | ((colour >> 10) & 0x3F) << 5 | ((colour >> 3) & 0x1F); // 24-bit colour to 16-bit colour conversion.
		batchKeys[count + 5] = cachedTextBackgroundColourKey;
		batchValues[count + 5] = ((background_colour >> 19) & 0x1F) << 11 | ((background_colour >> 10) & 0x3F) << 5 | ((background_colour >> 3) & 0x1F); // 24-bit colour to 16-bit colour conversion.
		batchKeys[count + 6] = cachedTextDrawKey;
		batchValues[count + 6] = 1;
		final var length = FlatDictionary.serialise(buffer, 0, batchKeys, batchValues, 0, count + 7, cachedMetadata);
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

		private final StringBuilder text = new StringBuilder();
		public int x, y, size, colour, background_colour;

		@Override
		protected void clear() {
			text.setLength(0);
			x = 0;
			y = 0;
			size = 0;
			colour = 0;
			background_colour = 0;
		}

		@Override
		protected void argument(int index) {
			if (index == 0) {
				x();
			} else if (index == 1) {
				y();
			} else if (index == 2) {
				text();
			} else if (index == 3) {
				size();
			} else if (index == 4) {
				colour();
			} else if (index == 5) {
				background_colour();
			} else {
				throw new RuntimeException("Unexpected argument: '" + index + "'.");
			}
		}

		@Override
		protected void argument(CharSequence name) {
			throw new RuntimeException("Unexpected argument: '" + name + "'.");
		}

		private void text() {
			read(text);
			consume();
		}

		private void x() {
			x = (int) number();
			consume();
		}

		private void y() {
			y = (int) number();
			consume();
		}

		private void size() {
			size = (int) number();
			consume();
		}

		private void colour() {
			colour = (int) number();
			consume();
		}

		private void background_colour() {
			background_colour = (int) number();
			consume();
		}

	}

}
