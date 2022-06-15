/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

import java.util.NoSuchElementException;
import java.util.Set;

public class BootstrapMetadata implements Metadata { // Note: Immutable.

	public static Metadata FIELD_METADATA = new FieldMetadata();

	public static final int NAME_KEY = 0;
	public static final int TYPE_KEY = 1;
	public static final int SIZE_KEY = 2;
	public static final int SPAN_KEY = 3;
	public static final int GETTABLE_KEY = 4;
	public static final int SETTABLE_KEY = 5;
	public static final int IDEMPOTENT_KEY = 6;
	public static final int MIN_VALUE_KEY = 7;
	public static final int MAX_VALUE_KEY = 8;
	public static final int UNITS_KEY = 9;
	public static final int DEBUG_KEY = 10;
	public static final int SYSTEM_KEY = 11;
	public static final int MODULE_KEY = 12;

	@Override
	public String name(int key) {
		return "metadata_" + key;
	}

	@Override
	public int type(int key) {
		return FieldType.DICTIONARY;
	}

	@Override
	public int size(int key) {
		return 0;
	}

	@Override
	public int span(int key) {
		return 1;
	}

	@Override
	public boolean debug(int key) {
		return false;
	}

	@Override
	public boolean system(int key) {
		return false;
	}

	@Override
	public String module(int key) {
		return null;
	}

	@Override
	public int floor(int key) {
		return key;
	}

	@Override
	public Metadata metadata(int key) {
		return FIELD_METADATA;
	}

	@Override
	public Set<Integer> keys() {
		throw new IllegalStateException("Bootstrap metadata is not enumerable.");
	}

	private static class FieldMetadata implements Metadata { // Note: Immutable.

		@Override
		public String name(int key) {
			switch (key) {
				case NAME_KEY:
					return "name";
				case TYPE_KEY:
					return "type";
				case SIZE_KEY:
					return "size";
				case SPAN_KEY:
					return "span";
				case GETTABLE_KEY:
					return "gettable";
				case SETTABLE_KEY:
					return "settable";
				case IDEMPOTENT_KEY:
					return "idempotent";
				case MIN_VALUE_KEY:
					return "min_value";
				case MAX_VALUE_KEY:
					return "max_value";
				case UNITS_KEY:
					return "units";
				case DEBUG_KEY:
					return "debug";
				case SYSTEM_KEY:
					return "system";
				case MODULE_KEY:
					return "module";
				default:
					throw new NoSuchElementException("Unknown metadata field.");
			}
		}

		@Override
		public int type(int key) {
			switch (key) {
				case NAME_KEY:
					return FieldType.UTF8_STRING;
				case TYPE_KEY:
					return FieldType.UINT;
				case SIZE_KEY:
					return FieldType.UINT;
				case SPAN_KEY:
					return FieldType.UINT;
				case GETTABLE_KEY:
					return FieldType.BOOLEAN;
				case SETTABLE_KEY:
					return FieldType.BOOLEAN;
				case IDEMPOTENT_KEY:
					return FieldType.BOOLEAN;
				case MIN_VALUE_KEY:
					return FieldType.UINT;
				case MAX_VALUE_KEY:
					return FieldType.UINT;
				case UNITS_KEY:
					return FieldType.UTF8_STRING;
				case DEBUG_KEY:
					return FieldType.BOOLEAN;
				case SYSTEM_KEY:
					return FieldType.BOOLEAN;
				case MODULE_KEY:
					return FieldType.UTF8_STRING;
				default:
					throw new NoSuchElementException("Unknown metadata field.");
			}
		}

		@Override
		public int size(int key) {
			switch (key) {
				case NAME_KEY:
					return 0; // i.e. Variable.
				case TYPE_KEY:
					return 1;
				case SIZE_KEY:
					return 1;
				case SPAN_KEY:
					return 4;
				case GETTABLE_KEY:
					return 1;
				case SETTABLE_KEY:
					return 1;
				case IDEMPOTENT_KEY:
					return 1;
				case MIN_VALUE_KEY:
					return 0; // i.e. Variable.
				case MAX_VALUE_KEY:
					return 0; // i.e. Variable.
				case UNITS_KEY:
					return 0; // i.e. Variable.
				case DEBUG_KEY:
					return 1;
				case SYSTEM_KEY:
					return 1;
				case MODULE_KEY:
					return 0; // i.e. Variable.
				default:
					throw new NoSuchElementException("Unknown metadata field.");
			}
		}

		@Override
		public int span(int key) {
			return 1;
		}

		@Override
		public boolean debug(int key) {
			return false;
		}

		@Override
		public boolean system(int key) {
			return false;
		}

		@Override
		public String module(int key) {
			return null;
		}
		
		@Override
		public int floor(int key) {
			return key;
		}

		@Override
		public Metadata metadata(int key) {
			throw new IllegalStateException("Field is not a dictionary.");
		}

		@Override
		public Set<Integer> keys() {
			throw new IllegalStateException("Bootstrap metadata is not enumerable.");
		}
		
	}

}
