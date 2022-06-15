/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.uavcan;

public interface ServiceHandler {

	public void handle(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length);

}
