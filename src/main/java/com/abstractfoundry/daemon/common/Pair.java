/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

public class Pair<H, T> {

	private final H head;
	private final T tail;

	public Pair(H head, T tail) {
		this.head = head;
		this.tail = tail;
	}

	public H getHead() {
		return head;
	}

	public T getTail() {
		return tail;
	}

}
