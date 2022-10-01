/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value="/") // Note: Endpoint path is determined by the (external) ServerEndpointConfig.
public class WebSocketServer {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
	
	private GenericSocketClientHandler genericHandler;

	public void injectHandler(GenericSocketClientHandler socketClientHandler) {
		this.genericHandler = socketClientHandler;
	}

	@OnOpen
	public void onOpen(Session session) {
		logger.info("WebSocket client connection opened.");
	}

	@OnMessage
	public void onMessage(Session session, ByteBuffer command) {
		final var remote = session.getBasicRemote();
		final TransmissionFunction send = buffer -> remote.sendBinary(buffer);
		boolean retry;
		try {
			while (retry = genericHandler.handle(command, send)) {
				LockSupport.parkNanos(10_000_000L); // Wait at least 10 ms to avoid starving the inbox thread. // TODO: We should use random exponential backoff (and also perhaps write something to the log).
			}
		} catch (RuntimeException exception) {
			logger.error("Error processing command.", exception); // TODO: Send the error back to the client (also allowing the client to reclaim the correlation ID).
		}
	}

	@OnClose
	public void onClose(Session session, CloseReason reason) {
		logger.info("WebSocket client connection closed.");
	}

}
