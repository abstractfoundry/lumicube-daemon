/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.uavcan.Node;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebServer {

	private final Server server;

	private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
	private final ServerConnector connector;

	public WebServer(Node daemonNode, ExecutorService globalPool, Store store, ScriptExecutor scriptExecutor, int port, GenericSocketClientHandlerSupplier handlerSupplier) {
		var pool = new QueuedThreadPool();
		pool.setName("Foundry Web Client");
		this.server = new Server(pool);
		connector = new ServerConnector(server);
		connector.setPort(port);
		server.addConnector(connector);

		ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		handler.setContextPath("/");
		try {
			handler.setBaseResource(
				Resource.newResource(
					WebServer.class.getResource("/META-INF/resources/static/").toExternalForm()
				)
			);
			handler.setWelcomeFiles(new String[] { "index.html" });
		} catch (IOException exception) {
			logger.error("Exception whilst creating base resource.", exception);
			return;
		}
		server.setHandler(handler);

		ResourceConfig jerseyConfig = new ResourceConfig();
		jerseyConfig.register(MultiPartFeature.class); // Required to upload files via Jersey.
		jerseyConfig.packages("com.abstractfoundry.daemon.rest.resource");

		jerseyConfig.register(new AbstractBinder() { @Override protected void configure() {
			bindFactory(new ImmediateFactory<>(daemonNode)).to(Node.class).in(RequestScoped.class);
		}});

		jerseyConfig.register(new AbstractBinder() { @Override protected void configure() {
			bindFactory(new ImmediateFactory<>(store)).to(Store.class).in(RequestScoped.class);
		}});

		jerseyConfig.register(new AbstractBinder() { @Override protected void configure() {
			bindFactory(new ImmediateFactory<>(globalPool)).to(ExecutorService.class).in(RequestScoped.class);
		}});

		jerseyConfig.register(new AbstractBinder() { @Override protected void configure() {
			bindFactory(new ImmediateFactory<>(scriptExecutor)).to(ScriptExecutor.class).in(RequestScoped.class);
		}});

		jerseyConfig.register(new AbstractBinder() { @Override protected void configure() {
			bindFactory(new ImmediateFactory<>(handlerSupplier)).to(GenericSocketClientHandlerSupplier.class).in(RequestScoped.class);
		}});

		ServletContainer jerseyServlet = new ServletContainer(jerseyConfig);
		handler.addServlet(new ServletHolder(jerseyServlet), "/api/v1/*");
		handler.addServlet(DefaultServlet.class, "/");

		JakartaWebSocketServletContainerInitializer.configure(handler, (context, container) ->
			container.addEndpoint(
				ServerEndpointConfig.Builder
					.create(WebSocketServer.class, "/socket")
					.configurator(new ServerEndpointConfig.Configurator()
						{
							@Override
							public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
								var instance = super.getEndpointInstance(endpointClass);
								var cast = (WebSocketServer) instance;
								var handler = handlerSupplier.get();
								cast.injectHandler(handler);
								return instance;
							}
						}
					)
					.build()
			)
		);
	}

	public void start() throws Exception {
		server.start();
	}

}
