/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.settings;

import java.io.File;

public class AbstractFoundryDirectory {

	private final static String HOME = System.getProperty("user.home");

	public static File probe() {
		var result = new File(HOME, "AbstractFoundry");
		result.mkdirs();
		return result;
	}

	public static File probeDaemonDirectory() {
		var result = new File(probe(), "Daemon");
		result.mkdirs();
		return result;
	}

	public static File probeDaemonSoftwareDirectory() {
		var result = new File(probeDaemonDirectory(), "Software");
		result.mkdirs();
		return result;
	}

	public static File probeDaemonScriptsDirectory() {
		var result = new File(probeDaemonDirectory(), "Scripts");
		result.mkdirs();
		return result;
	}

}
