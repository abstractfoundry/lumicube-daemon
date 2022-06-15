/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server;

import org.glassfish.hk2.api.Factory;

class ImmediateFactory<T> implements Factory<T> {

	private final T instance;

	public ImmediateFactory(T instance) {
		this.instance = instance;
	}

	@Override
	public T provide() {
		return instance;
	}

	@Override
	public void dispose(T instance) {

	}

}
