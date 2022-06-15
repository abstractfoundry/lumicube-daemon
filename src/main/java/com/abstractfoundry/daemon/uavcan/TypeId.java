/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

public class TypeId {
	// Broadcast
	public static final int ALLOCATION = 1;
	public static final int NODE_STATUS = 341;
	public static final int PUBLISHED_FIELDS = 20000;

	// Service
	public static final int GET_NODE_INFO = 1;
	public static final int SUBSCRIBE_DEFAULT_FIELDS = 200;
	public static final int GET_PREFERRED_NAME = 202;
	public static final int ENUMERATE_FIELDS = 204;
	public static final int SET_FIELDS = 216; // to 231 (inclusive).
}
