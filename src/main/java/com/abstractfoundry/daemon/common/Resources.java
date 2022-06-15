/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Resources {

	private static final String RESOURCE_ROOT = "/META-INF/resources";

	public static void extract(Path resourcePath, Path expandedPath, String... fileNames) throws IOException {
		if (expandedPath.toFile().mkdir()) {
			for (var fileName : fileNames) {
				var source = Resources.class.getResourceAsStream(RESOURCE_ROOT + "/" + resourcePath + "/" + fileName);
				if (source != null) {
					var target = expandedPath.resolve(fileName);
					Files.copy(source, target);
				} else {
					throw new IOException("Resource not found: " + fileName + ".");
				}
			}
		} else {
			throw new IOException("Failed to extract the Python library.");
		}
	}

}
