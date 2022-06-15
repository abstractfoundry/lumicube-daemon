/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.redis;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.settings.AbstractFoundryDirectory;
import com.abstractfoundry.daemon.utility.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//	Build script:

//	#!/bin/bash
//
//	apt install qemu-user-static binfmt-support
//
//	cat > Dockerfile <<\EOF
//
//	FROM debian:buster-20210111
//
//	RUN apt update && apt install -y git build-essential autoconf libtool libssl-dev
//
//	RUN git clone --branch 6.0.16 https://github.com/redis/redis/ /root/Redis
//	WORKDIR /root/Redis
//	RUN make
//
//	RUN git clone --recursive --branch v1.6.9 https://github.com/RedisTimeSeries/RedisTimeSeries.git /root/RedisTimeSeries
//	WORKDIR /root/RedisTimeSeries
//	RUN ./deps/readies/bin/getpy3
//	RUN make build
//
//	EOF
//
//	docker image rm debian:buster-20210111 # Work around Docker not supporting several platform-specific versions of the same image.
//	docker build --platform linux/amd64 -t build-redis-x64 .
//	docker run --rm --entrypoint cat build-redis-x64 /root/Redis/src/redis-server > redis-server-x64
//	docker run --rm --entrypoint cat build-redis-x64 /root/RedisTimeSeries/bin/linux-x64-release/redistimeseries.so > redistimeseries-x64.so
//	chmod +x redis-server-x64 redistimeseries-x64.so
//
//	docker image rm debian:buster-20210111 # Work around Docker not supporting several platform-specific versions of the same image.
//	docker build --platform linux/arm/v7 -t build-redis-arm .
//	docker run --rm --entrypoint cat build-redis-arm /root/Redis/src/redis-server > redis-server-arm
//	docker run --rm --entrypoint cat build-redis-arm /root/RedisTimeSeries/bin/linux-arm32v7-release/redistimeseries.so > redistimeseries-arm.so
//	chmod +x redis-server-arm redistimeseries-arm.so

public class RedisLauncher {

	private static final Logger logger = LoggerFactory.getLogger(RedisLauncher.class);

	private static final String RESOURCE_ROOT = "/META-INF/resources/redis";
	private static final String CONTEXT_PREFIX = "foundry-redis-context-";

	private final int port;
	
	private Process process;

	public RedisLauncher(int port) {
		this.port = port;
	}

	public synchronized void start() throws IOException {
		if (process != null) {
			throw new IllegalStateException("Redis is already started.");
		}
		var platformName = Platform.getCanonicalName();
		var foundryDaemonDirectory = AbstractFoundryDirectory.probeDaemonDirectory();
		var temporaryDirectory = Files.createTempDirectory(CONTEXT_PREFIX);
		var fileNames = new String[] {
			"redis-server",
			"redistimeseries.so"
		};
		for (var fileName : fileNames) {
			var source = ScriptExecutor.class.getResourceAsStream(RESOURCE_ROOT + "/" + platformName + "/" + fileName);
			var target = temporaryDirectory.resolve(fileName);
			Files.copy(source, target);
		}
		var server = new File(temporaryDirectory.toString(), "redis-server");
		var plugin = new File(temporaryDirectory.toString(), "redistimeseries.so");
		server.setExecutable(true, false);
		plugin.setExecutable(true, false);
		var builder = new ProcessBuilder(server.getAbsolutePath(),
			"--port", Integer.toString(port),
			"--dbfilename", "foundry.db",
			"--loadmodule", plugin.getAbsolutePath(),
			"COMPACTION_POLICY", "avg:1s:6h;avg:1M:10d;avg:10M:100d;avg:1h:1000d;avg:1d:10000d",
			"RETENTION_POLICY", "300000",
			"DUPLICATE_POLICY", "LAST"
		)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD) // Note: Directing the output stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
			.redirectError(ProcessBuilder.Redirect.DISCARD) // Note: Directing the error stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
			.directory(foundryDaemonDirectory);
		process = builder.start();
	}

}
