/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.ThreadSafe;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;

public class SerialConnectedNode extends Node {

	private final SerialConnector serialConnector;

	public SerialConnectedNode(int selfId, ExecutorService globalPool, BroadcastHandler broadcastHandler, String devicePath) {
		super(selfId, globalPool, broadcastHandler);
		this.serialConnector = new SerialConnector(devicePath, this::receiveBroadcast, this::receiveRequest, this::receiveResponse);
	}

	@Override
	@ThreadSafe
	protected void sendBroadcast(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		serialConnector.sendBroadcast(sourceId, typeId, transferId, priority, buffer, offset, length);
	}

	@Override
	@ThreadSafe
	protected void sendRequest(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		serialConnector.sendRequest(sourceId, destinationId, typeId, transferId, priority, buffer, offset, length);
	}

	@Override
	@ThreadSafe
	protected void sendRequests(int sourceId, int destinationId, int typeId, int[] transferIds, int priority, ByteBuffer[] buffers, int count) {
		serialConnector.sendRequests(sourceId, destinationId, typeId, transferIds, priority, buffers, count);
	}

	@Override
	@ThreadSafe
	protected void sendResponse(int sourceId, int destinationId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) {
		serialConnector.sendResponse(sourceId, destinationId, typeId, transferId, priority, buffer, offset, length);
	}

	@Override
	public void start() {
		serialConnector.start();
	}

	public int inboxBacklog() {
		return serialConnector.inboxBacklog();
	}

	public int outboxBacklog() {
		return serialConnector.outboxBacklog();
	}

	public int collectorBacklog() {
		return serialConnector.collectorBacklog();
	}

}
