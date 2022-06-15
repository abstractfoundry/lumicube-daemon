/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.io.IOException;

public abstract class RecursiveDescentJsonParser {

	private final LightweightJsonParser json;

	public RecursiveDescentJsonParser() {
		json = new LightweightJsonParser();
	}

	public void parse(CharSequence data) {
		reset();
		json.parse(data, 0, data.length());
		begin();
	}

	protected boolean is(JsonToken token) {
		return json.currentToken() == token;
	}

	protected boolean consume() {
		return json.step();
	}

	protected boolean accept(JsonToken token) {
		if (json.currentToken() == token) {
			json.step();
			return true;
		} else {
			return false;
		}
	}

	protected boolean expect(JsonToken token) {
		if (accept(token)) {
			return true;
		} else {
			throw new RuntimeException("Expected " + token + ", but got " + json.currentToken() + ".");
		}
	}

	public void read(Appendable appendable) {
		try {
			json.getText(appendable);
		} catch (IOException exception) {
			throw new RuntimeException("Failed to append string.", exception);
		}
	}

	public long integer() {
		return json.getLongValue();
	}

	public double number() {
		if (json.currentToken() == JsonToken.VALUE_NUMBER_INT) {
			return (double) json.getLongValue();
		} else {
			return json.getDoubleValue();
		}
	}

	protected abstract void reset();

	protected abstract void begin();

}
