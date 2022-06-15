/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */
package com.abstractfoundry.daemon.bus;

import java.util.Set;

public interface Metadata { // Note: Immutable.

	public String name(int key);
	public int type(int key);
	public int size(int key);
	public int span(int key);
	public boolean debug(int key);
	public boolean system(int key);
	public String module(int key);
	public int floor(int key);
	public Metadata metadata(int key);
	public Set<Integer> keys();

}
