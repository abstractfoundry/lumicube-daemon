/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import io.github.green4j.jelly.JsonNumber;
import io.github.green4j.jelly.JsonParser;
import io.github.green4j.jelly.JsonParserListener;

import java.io.IOException;

public final class LightweightJsonParser {

	private final JsonParser parser;
	private final Listener listener;

	private JsonParser.Next iterator;

	private JsonToken token;
	private CharSequence text;
	private long integer;
	private double real;
	private boolean complete;

	public LightweightJsonParser() {
		parser = new JsonParser();
		listener = new Listener();
		parser.setListener(listener);
		reset();
	}

	public void reset() {
		parser.reset();
		iterator = null;
		token = null;
		text = null;
		integer = 0;
		real = 0;
		complete = false;
	}

	public void parse(CharSequence data, int start, int length) {
		reset();
		iterator = parser.parse(data, start, length);
	}

	public boolean step() {
		if (iterator != null) {
			iterator = iterator.next();
			return true;
		} else {
			if (!complete) {
				try {
					parser.eoj();
				} finally {
					complete = true;
				}
			}
			return false;
		}
	}

	public JsonToken currentToken() {
		return token;
	}

	public int getText(Appendable appendable) throws IOException {
		if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
			appendable.append(text);
		} else {
			throw new IllegalStateException("Token is not JsonToken.FIELD_NAME or JsonToken.VALUE_STRING.");
		}
		return text.length();
	}

	public long getLongValue() {
		if (token == JsonToken.VALUE_NUMBER_INT) {
			return integer;
		} else {
			throw new IllegalStateException("Token is not JsonToken.VALUE_NUMBER_INT.");
		}
	}

	public double getDoubleValue() {
		if (token == JsonToken.VALUE_NUMBER_FLOAT) {
			return real;
		} else {
			throw new IllegalStateException("Token is not JsonToken.VALUE_NUMBER_FLOAT.");
		}
	}

	private final class Listener implements JsonParserListener {

		@Override
		public boolean onObjectStarted() {
			token = JsonToken.START_OBJECT;
			return false;
		}

		@Override
		public boolean onObjectMember(CharSequence name) {
			text = name;
			token = JsonToken.FIELD_NAME;
			return false;
		}

		@Override
		public boolean onObjectEnded() {
			token = JsonToken.END_OBJECT;
			return false;
		}

		@Override
		public boolean onArrayStarted() {
			token = JsonToken.START_ARRAY;
			return false;
		}

		@Override
		public boolean onArrayEnded() {
			token = JsonToken.END_ARRAY;
			return false;
		}

		@Override
		public boolean onStringValue(CharSequence data) {
			text = data;
			token = JsonToken.VALUE_STRING;
			return false;
		}

		@Override
		public boolean onNumberValue(JsonNumber number) {
			final var exponent = number.exp();
			final var mantissa = number.mantissa();
			if (exponent == 0) {
				integer = mantissa;
				token = JsonToken.VALUE_NUMBER_INT;
			} else {
				real = (double) mantissa * Math.pow(10, exponent);
				token = JsonToken.VALUE_NUMBER_FLOAT;
			}
			return false;
		}

		@Override
		public boolean onTrueValue() {
			token = JsonToken.VALUE_TRUE;
			return false;
		}

		@Override
		public boolean onFalseValue() {
			token = JsonToken.VALUE_FALSE;
			return false;
		}

		@Override
		public boolean onNullValue() {
			token = JsonToken.VALUE_NULL;
			return false;
		}

		@Override
		public void onJsonStarted() {
			if (token != null) {
				throw new IllegalStateException("Parser was not properly reset.");
			}
		}

		@Override
		public void onJsonEnded() {
			token = null;
		}

		@Override
		public void onError(String error, int position) {
			throw new RuntimeException(error + " at position: " + position + ".");
		}

	}

}
