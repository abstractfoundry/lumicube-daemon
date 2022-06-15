/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.util.function.Supplier;

public class Lazy<T> {

	private final Supplier<T> initialiser;
	private boolean initialised = false;
	private T value;

	public Lazy(Supplier<T> initialiser) {
		this.initialiser = initialiser;
	}

	public T get() {
		if (!initialised) {
			value = initialiser.get();
			initialised = true;
		}
		return value;
	}

}
