/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class FluentThreadFactory implements ThreadFactory {
	
	private final AtomicInteger counter = new AtomicInteger(1);

	private String name = null;
	private boolean daemon = false;
	private boolean append = false;

	public FluentThreadFactory setName(String name) {
		this.name = name;
		return this;
	}

	public FluentThreadFactory setDaemon(boolean daemon) {
		this.daemon = daemon;
		return this;
	}

	public FluentThreadFactory appendInstance() {
		this.append = true;
		return this;
	}

	@Override
	public Thread newThread(final Runnable runnable) {
		var instance = counter.getAndIncrement();
		Thread thread = new Thread(runnable);
		if (name != null) {
			var suffix = append ? ("-" + instance) : "";
			thread.setName(name + suffix);
		}
		if (daemon) {
			thread.setDaemon(true);
		} else {
			thread.setDaemon(false);
		}
		return thread;
	}
}
