/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

public enum JsonToken {
	START_OBJECT,
	END_OBJECT,
	START_ARRAY,
	END_ARRAY,
	FIELD_NAME,
	VALUE_STRING,
	VALUE_NUMBER_INT,
	VALUE_NUMBER_FLOAT,
	VALUE_TRUE,
	VALUE_FALSE,
	VALUE_NULL,
}
