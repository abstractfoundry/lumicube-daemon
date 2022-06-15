/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

public class BackoffException extends Exception {

	public BackoffException(String message) {
		super(message);
	}

}
