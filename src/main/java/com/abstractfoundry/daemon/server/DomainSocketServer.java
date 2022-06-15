/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.common.FluentThreadFactory;
import com.abstractfoundry.daemon.common.Pause;
import com.abstractfoundry.daemon.uavcan.Node;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DomainSocketServer extends Thread { // TODO: Factor up common logic with TcpSocketServer.

	private static final Logger logger = LoggerFactory.getLogger(DomainSocketServer.class);
	
	private final Node daemonNode;
	private final Path socketPath;
	private final GenericSocketClientHandlerSupplier handlerSupplier;
	private final ExecutorService clientThreadPool;

	public DomainSocketServer(Node daemonNode, Path socketPath, GenericSocketClientHandlerSupplier handlerSupplier) {
		super("Foundry Server Domain Socket");
		this.setDaemon(true);
		this.daemonNode = daemonNode;
		this.socketPath = socketPath;
		this.handlerSupplier = handlerSupplier;
		this.clientThreadPool = Executors.newCachedThreadPool(
			new FluentThreadFactory()
				.setName("Foundry Client Domain Socket")
				.setDaemon(true)
		);
	}

	@Override
	public void run() {
		ServerSocketChannel serverSocketChannel;
		try {
			serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
			Files.deleteIfExists(socketPath); // Delete old socket if it exists.
			var socketAddress = UnixDomainSocketAddress.of(socketPath);
			serverSocketChannel.bind(socketAddress);
		} catch (IOException exception) {
			logger.error("Failed to bind domain socket.", exception);
			return;
		}
		while (!Thread.interrupted()) {
			try {
				var clientSocketChannel = serverSocketChannel.accept();
				var genericHandler = handlerSupplier.get();
				var clientHandler = new SocketChannelClientHandler(daemonNode, clientSocketChannel, genericHandler);
				clientThreadPool.execute(clientHandler);
			} catch (IOException exception) {
				logger.error("I/O exception instantiating domain socket client handler.", exception);
				Pause.onError();
			} catch (RuntimeException exception) {
				logger.error("Unhandled exception.", exception);
				Pause.onError();
			}
		}
	}

}
