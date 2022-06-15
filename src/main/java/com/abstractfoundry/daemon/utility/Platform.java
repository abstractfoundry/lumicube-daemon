/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.utility;

public class Platform {

	public static String getCanonicalName() {
		return System.getProperty("os.arch").toLowerCase().contains("arm") ? "arm" : "x64"; // TODO: Make generic.
	}

}
