/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.audio.SpeakerThread;
import com.abstractfoundry.daemon.audio.VirtualMicrophone;
import com.abstractfoundry.daemon.common.FluentThreadFactory;
import com.abstractfoundry.daemon.heartbeat.Heartbeat;
import com.abstractfoundry.daemon.python.service.GlobalPythonService;
import com.abstractfoundry.daemon.redis.RedisLauncher;
import com.abstractfoundry.daemon.server.DomainSocketServer;
import com.abstractfoundry.daemon.server.GenericSocketClientHandler;
import com.abstractfoundry.daemon.server.GenericSocketClientHandlerSupplier;
import com.abstractfoundry.daemon.server.TcpSocketServer;
import com.abstractfoundry.daemon.server.WebServer;
import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import com.abstractfoundry.daemon.utility.BroadcastHandler;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Daemon implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(Daemon.class);

	private static final int DAEMON_SOCKET_PORT = 2020;
	private static final int DAEMON_WEBSERVER_PORT = 8686;
	private static final Path DAEMON_SOCKET_PATH = Path.of("/", "tmp", "foundry_daemon.sock");
	private static final Path GLOBAL_PYTHON_SERVICE_PATH = Path.of("/", "tmp", "foundry_python_service.sock");
	
	private static final String REDIS_HOST = "127.0.0.1";
	private static final int REDIS_PORT = 6380;

	public static final String MANIFEST_VERSION = Daemon.class.getPackage().getImplementationVersion();
	public static final String DAEMON_VERSION = MANIFEST_VERSION != null ? MANIFEST_VERSION : "0.0-SNAPSHOT";

	private final ExecutorService globalPool;
	private final RedisLauncher redisLauncher;
	private final Store store;
	private final VirtualMicrophone virtualMicrophone;
	private final BroadcastHandler broadcastHandler;
	private final SerialConnectedNode daemonNode;
	private final GlobalPythonService globalPythonService;
	private final ScriptExecutor scriptExecutor;
	private final Heartbeat heartbeat;
	private final GenericSocketClientHandlerSupplier handlerSupplier;
	private final DomainSocketServer domainSocketServer;
	private final TcpSocketServer tcpSocketServer;
	private final WebServer webServer;
	private final SpeakerThread speakerThread;

	public Daemon(String[] arguments) {
		var devicePath = arguments.length > 0 ? arguments[0] : null;
		var daemonId = 1;
		globalPool = Executors.newCachedThreadPool(
			new FluentThreadFactory()
				.setName("Foundry Global")
				.appendInstance()
		);
		redisLauncher = new RedisLauncher(REDIS_PORT);
		store = new Store(REDIS_HOST, REDIS_PORT);
		virtualMicrophone = new VirtualMicrophone();
		broadcastHandler = new BroadcastHandler(store, virtualMicrophone);
		daemonNode = new SerialConnectedNode(daemonId, globalPool, broadcastHandler::handle, devicePath);
		globalPythonService = new GlobalPythonService(globalPool, GLOBAL_PYTHON_SERVICE_PATH);
		scriptExecutor = new ScriptExecutor(globalPool, store, globalPythonService);
		heartbeat = new Heartbeat(daemonNode, store, scriptExecutor);
		handlerSupplier = () -> new GenericSocketClientHandler(daemonNode, globalPool, store, globalPythonService); // One for each client.
		domainSocketServer = new DomainSocketServer(daemonNode, DAEMON_SOCKET_PATH, handlerSupplier);
		tcpSocketServer = new TcpSocketServer(daemonNode, DAEMON_SOCKET_PORT, handlerSupplier);
		webServer = new WebServer(daemonNode, globalPool, store, scriptExecutor,
			DAEMON_WEBSERVER_PORT, handlerSupplier
		);
		speakerThread = new SpeakerThread(daemonNode, store);
	}

	@Override
	public void run() {
		logger.info("Starting daemon version {}.", DAEMON_VERSION);
		try {
			redisLauncher.start();
			daemonNode.start();
			globalPythonService.start();
			scriptExecutor.start();
			domainSocketServer.start();
			tcpSocketServer.start();
			webServer.start();
			speakerThread.start();
			var tick = System.nanoTime();
			while (!Thread.interrupted()) {
				var pause = tick - System.nanoTime();
				if (pause > 0) {
					LockSupport.parkNanos(pause);
				}
				heartbeat.pulse();
				tick += 1_000_000_000L;
			}
		} catch (Exception exception) {
			logger.error("Failed to start the daemon.", exception);
		} finally {
			globalPythonService.stop();
		}
	}

	public static void main(String[] arguments) {
		var daemon = new Daemon(arguments);
		daemon.run();
	}

}
