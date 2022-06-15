/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.resource;

import com.abstractfoundry.daemon.Daemon;
import com.abstractfoundry.daemon.settings.AbstractFoundryDirectory;
import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.utility.Platform;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/tools")
public class ToolsResource {

	private static final Logger logger = LoggerFactory.getLogger(ToolsResource.class);

	private static final String LATEST_VERSION_URL = "https://www.abstractfoundry.com/lumicube/download/latest_daemon.txt";
	private static final String APPIMAGE_DOWNLOAD_URL_PREFIX = "https://www.abstractfoundry.com/lumicube/download/";
	private static final String REBOOT_PATH = "/usr/sbin/reboot";

	private static final String PLATFORM_NAME = Platform.getCanonicalName();
	private static final List<String> LAUNCH_SCRIPT = List.of(
		"#!/bin/bash",
		"CONTAINING_DIRECTORY=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" >/dev/null 2>&1 && pwd )\"",
		"\"$CONTAINING_DIRECTORY\"/Software/Daemon-\"$( cat \"$CONTAINING_DIRECTORY\"/version.txt )\"-" + PLATFORM_NAME + ".AppImage \"$@\""
	);

	@GET
	@Path("/upgrade_available")
	@Produces(MediaType.APPLICATION_JSON)
	public Response upgradeAvailable(@Context ExecutorService globalPool) {
		try {
			var version = queryUpgradeVersion();
			var available = (version != null);
			return Response.ok(available).build();
		} catch (IOException exception) {
			logger.warn("Unable to check for upgrade.", exception);
			return Response.ok(false).build();
		}
	}

	@POST
	@Path("/upgrade")
	@Produces(MediaType.TEXT_PLAIN)
	public Response upgrade(@Context ExecutorService globalPool, @Context Store store) {
		try {
			var version = queryUpgradeVersion();
			if (version != null) {
				logger.info("Upgrading to version " + version.toString() + ".");
				globalPool.submit(() -> {
					try {
						var previous = store.setUpgradeInProgress(true);
						if (previous != false) {
							throw new RuntimeException("Ignoring request, upgrade already in progress.");
						}
						var daemonDirectory = AbstractFoundryDirectory.probeDaemonDirectory();
						var daemonSoftwareDirectory = AbstractFoundryDirectory.probeDaemonSoftwareDirectory();
						var fileName = "Daemon-" + version.toString() + "-" + PLATFORM_NAME + ".AppImage";
						var downloadURL = new URL(APPIMAGE_DOWNLOAD_URL_PREFIX + fileName);
						var installedFile = new File(daemonSoftwareDirectory, fileName);
						Files.copy(downloadURL.openStream(), installedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
						installedFile.setExecutable(true, false);
						logger.info("Downloaded new executable AppImage to: {}.", installedFile);
						var launchFile = new File(daemonDirectory, "launch.sh");
						Files.write(launchFile.toPath(), LAUNCH_SCRIPT);
						launchFile.setExecutable(true, false);
						logger.info("Generic executable launch script asserted.");
						var versionFile = new File(daemonDirectory, "version.txt");
						var versionString = version.toString();
						Files.write(versionFile.toPath(), List.of(versionString));
						logger.info("Version file updated.");
						var reboot = new File(REBOOT_PATH);
						if (reboot.canExecute()) {
							logger.info("Rebooting the system now.");
							var process = new ProcessBuilder(REBOOT_PATH).start();
						} else {
							logger.warn("Unable to automatically reboot the system, manual intervention required.");
						}
					} catch (RuntimeException | IOException exception) {
						logger.error("Error whilst upgrading daemon.", exception);
					} finally {
						store.setUpgradeInProgress(false);
					}
				});
				return Response.ok("Upgrading to version " + version.toString() + ", after the download completes the system will restart.").build();
			} else {
				logger.info("Daemon already up to date.");
				return Response.ok("Daemon already up to date.").build();
			}
		} catch (RuntimeException | IOException exception) {
			logger.error("Unable to perform the upgrade.", exception);
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity("Unable to perform the upgrade, please check your product is connected to the internet.").build();
		}
	}

	private static ComparableVersion queryUpgradeVersion() throws IOException {
		logger.info("Querying latest daemon version.");
		var baseline = new ComparableVersion(Daemon.DAEMON_VERSION);
		var latest = queryLatestVersion();
		logger.info("Current version = {};  Latest version = {}.", baseline, latest);
		if (latest.compareTo(baseline) > 0) {
			return latest;
		} else {
			return null;
		}
	}

	private static ComparableVersion queryLatestVersion() throws IOException {
		var url = new URL(LATEST_VERSION_URL);
		var buffer = new ByteArrayOutputStream();
		url.openStream().transferTo(buffer);
		var string = buffer.toString(StandardCharsets.UTF_8).strip();
		return new ComparableVersion(string);
	}

}
