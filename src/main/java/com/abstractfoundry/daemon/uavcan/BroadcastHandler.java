/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.uavcan;

public interface BroadcastHandler {

	public void handle(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length);

}
