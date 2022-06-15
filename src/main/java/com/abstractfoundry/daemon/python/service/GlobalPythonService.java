/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.python.service;

import com.abstractfoundry.daemon.common.LineReaderTask;
import com.abstractfoundry.daemon.common.Resources;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.LockSupport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jetty.connector.JettyConnectorProvider;
import org.glassfish.jersey.jetty.connector.JettyHttpClientSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalPythonService {

	private static final Logger logger = LoggerFactory.getLogger(GlobalPythonService.class);

	private static final String CONTEXT_PREFIX = "foundry-global-service-context-";

	private final ExecutorService globalPool;
	private final Client client; // Note: This should be thread-safe, although it is not properly specified in the JAX-RS / Jersey documentation.
	private final WebTarget target; // Note: This should be thread-safe, although it is not properly specified in the JAX-RS / Jersey documentation.
	
	private Path contextDirectory;
	private Process process;

	public GlobalPythonService(ExecutorService globalPool, Path path) {
		var connector = ClientConnector.forUnixDomain(path);
		var timeout = Duration.ofDays(120); // Effectively infinite timeout, allowing long duration connections with little activity.
		connector.setIdleTimeout(timeout);
		var transport = new HttpClientTransportOverHTTP(connector);
		var http = new HttpClient(transport);

		var config = new ClientConfig();
		config.connectorProvider(new JettyConnectorProvider());
		config.register(new JettyHttpClientSupplier(http));

		this.globalPool = globalPool;
		this.client = ClientBuilder.newClient(config);
		this.target = client.target("http://localhost");
	}

	public int interruptExecutingMethods() {
		var response = (Map) target.path("interrupt_executing_methods").request()
			.post(Entity.entity("{}", MediaType.APPLICATION_JSON), Map.class);
		return (int) response.get("phase");
	}

	public Object invokeModuleMethod(CharSequence moduleNameView, CharSequence methodNameView, CharSequence jsonView) {
		Map invocation = new HashMap();
		invocation.put("module", moduleNameView.toString());
		invocation.put("method", methodNameView.toString());
		invocation.put("json", jsonView.toString()); // TODO: Don't nest JSON as a string?
		var response = (Map) target.path("invoke_module_method").request()
			.post(Entity.entity(invocation, MediaType.APPLICATION_JSON), Map.class);
		if (!response.containsKey("result")) {
			logger.error("Error invoking method: " + response);
			if (!response.containsKey("error")) {
				throw new RuntimeException("Error invoking method.");
			} else {
				throw new RuntimeException((String) response.get("error"));
			}
		} else {
			return response.get("result");
		}
	}

	public void start() throws IOException {
		contextDirectory = Files.createTempDirectory(CONTEXT_PREFIX);
		Resources.extract(Path.of("python", "global_service"), contextDirectory.resolve("global_service"),
			"__init__.py", // Note: Sadly Java doesn't allow enumeration of resource files, so we must list them explicitly.
			"service.py"
		);
		Resources.extract(Path.of("python", "foundry_api"), contextDirectory.resolve("foundry_api"),
			"__init__.py", // Note: Sadly Java doesn't allow enumeration of resource files, so we must list them explicitly.
			"standard_library.py"
		);
		Resources.extract(Path.of("python", "fonts"), contextDirectory.resolve("fonts"),
			"slkscr.ttf"
		);

		var builder = new ProcessBuilder(
			"/usr/bin/python3", "-c", "import global_service; global_service.start()", contextDirectory.toString()
		);
		var environment = builder.environment();
		environment.put("PYTHONPATH", contextDirectory.toString());
		builder.directory(contextDirectory.toFile());
		process = builder.start();
		globalPool.submit(new LineReaderTask(process.getInputStream(), line -> logger.warn("{}.", line)));
		globalPool.submit(new LineReaderTask(process.getErrorStream(), line -> logger.error("{}.", line)));

		Exception cause = null;
		for (var attempt = 0; attempt < 5; attempt++) {
			try {
				var response = (Map) target.path("ping").request()
					.post(Entity.entity("{\"value\": 123}", MediaType.APPLICATION_JSON), Map.class);
				if (response.get("result").equals(123)) {
					return;
				}
			} catch (RuntimeException exception) {
				cause = exception;
			}
			LockSupport.parkNanos(1_000_000_000L);
		}
		if (cause != null) {
			logger.error("Failed to connect to service.", cause);
		} else {
			logger.error("Failed to connect to service.");
		}
	}

	public void stop() {
		client.close();
	}

}
