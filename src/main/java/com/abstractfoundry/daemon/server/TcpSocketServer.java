/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.common.FluentThreadFactory;
import com.abstractfoundry.daemon.common.Pause;
import com.abstractfoundry.daemon.uavcan.Node;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TcpSocketServer extends Thread { // TODO: Factor up common logic with DomainSocketServer.

	private static final Logger logger = LoggerFactory.getLogger(TcpSocketServer.class);

	private final Node daemonNode;
	private final int socketPort;
	private final GenericSocketClientHandlerSupplier handlerSupplier;
	private final ExecutorService clientThreadPool;

	public TcpSocketServer(Node daemonNode, int socketPort, GenericSocketClientHandlerSupplier handlerSupplier) {
		super("Foundry Server TCP Socket");
		this.setDaemon(true);
		this.daemonNode = daemonNode;
		this.socketPort = socketPort;
		this.handlerSupplier = handlerSupplier;
		this.clientThreadPool = Executors.newCachedThreadPool(
			new FluentThreadFactory()
				.setName("Foundry Client TCP Socket")
				.setDaemon(true)
		);
	}

	@Override
	public void run() {
		ServerSocketChannel serverSocketChannel;
		try {
			serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.INET);
			var socketAddress = new InetSocketAddress(socketPort);
			serverSocketChannel.bind(socketAddress);
		} catch (IOException exception) {
			logger.error("Failed to bind TCP socket.", exception);
			return;
		}
		while (!Thread.interrupted()) {
			try {
				var clientSocketChannel = serverSocketChannel.accept();
				var genericHandler = handlerSupplier.get();
				var clientHandler = new SocketChannelClientHandler(daemonNode, clientSocketChannel, genericHandler);
				clientThreadPool.execute(clientHandler);
			} catch (IOException exception) {
				logger.error("I/O exception instantiating TCP socket client handler.", exception);
				Pause.onError();
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception.", exception);
				Pause.onError();
			}
		}
	}

}
