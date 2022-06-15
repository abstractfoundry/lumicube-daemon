/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

public class FieldType {

		public static final int NULL = 0;
		public static final int RAW = 1; // Unsigned.
		public static final int ENUM = 2; // Signed.
		public static final int BOOLEAN = 3; // Signed.
		public static final int UINT = 4; // Unsigned.
		public static final int INT = 5; // Signed.
		public static final int FLOAT = 6; // Signed.
		public static final int TIME = 7; // Signed.
		public static final int UTF8_CHAR = 8;
		public static final int UTF8_STRING = 9;
		public static final int DICTIONARY = 10;

}
