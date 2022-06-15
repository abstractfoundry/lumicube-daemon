/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.util.concurrent.locks.LockSupport;

public class Pause {

	public static void onError() {
		LockSupport.parkNanos(1_000_000_000L); // Avoid thrashing in error state.
	}

}
