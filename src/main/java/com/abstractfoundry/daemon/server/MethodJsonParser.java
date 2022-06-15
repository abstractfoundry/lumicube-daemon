/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.common.JsonToken;
import com.abstractfoundry.daemon.common.RecursiveDescentJsonParser;

public abstract class MethodJsonParser extends RecursiveDescentJsonParser {

	private final StringBuilder buffer = new StringBuilder();

	@Override
	protected final void reset() {
		buffer.setLength(0);
		clear();
	}

	@Override
	protected final void begin() {
		expect(JsonToken.START_OBJECT);
		while (!accept(JsonToken.END_OBJECT)) {
			buffer.setLength(0);
			read(buffer); // Read the top-level field name (e.g. "arguments").
			consume();
			field();
		}
	}

	private void field() {
		if (CharSequence.compare(buffer, "arguments") == 0) {
			arguments();
		} else {
			throw new RuntimeException("Unexpected field: '" + buffer + "'.");
		}
	}

	private void arguments() {
		if (accept(JsonToken.START_ARRAY)) {
			var index = 0;
			while (!accept(JsonToken.END_ARRAY)) {
				argument(index++);
			}
		} else {
			expect(JsonToken.START_OBJECT);
			while (!accept(JsonToken.END_OBJECT)) {
				buffer.setLength(0);
				read(buffer); // Read the name (named argument / positional argument).
				consume();
				var first = buffer.charAt(0);
				if (first >= '0' && first <= '9') {
					var index = Integer.parseInt(buffer, 0, buffer.length(), 10);
					argument(index);
				} else {
					argument(buffer);
				}
			}
		}
	}

	protected abstract void clear();

	protected abstract void argument(int index);

	protected abstract void argument(CharSequence name);

}