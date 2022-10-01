/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.script;

import com.abstractfoundry.daemon.common.LineReaderTask;
import com.abstractfoundry.daemon.common.Resources;
import com.abstractfoundry.daemon.python.service.GlobalPythonService;
import com.abstractfoundry.daemon.store.Store;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScriptExecutor {

	private static final Logger logger = LoggerFactory.getLogger(ScriptExecutor.class);

	private static final String CONTEXT_PREFIX = "foundry-script-context-";
	private static final int KILL_TIMEOUT_MILLISECONDS = 500;

	private final ExecutorService globalPool;
	private final Store store;
	private final GlobalPythonService globalPythonService;

	private Path contextDirectory;
	private Process currentProcess;
	private int unautomatedLaunchCount = 0;

	public ScriptExecutor(ExecutorService globalPool, Store store, GlobalPythonService globalPythonService) {
		this.globalPool = globalPool;
		this.store = store;
		this.globalPythonService = globalPythonService;
	}

	public synchronized void start() throws IOException {
		contextDirectory = Files.createTempDirectory(CONTEXT_PREFIX);
		Resources.extract(Path.of("python", "foundry_api"), contextDirectory.resolve("foundry_api"),
			"__init__.py", // Note: Sadly Java doesn't allow enumeration of resource files, so we must list them explicitly.
			"standard_library.py",
			"script_launcher.py"
		);
		Resources.extract(Path.of("python", "noise"), contextDirectory.resolve("noise"),
			"open-simplex-noise-arm.so",
			"open-simplex-noise-x64.so"
		);
	}

	public synchronized void terminate() {
		try {
			globalPythonService.interruptExecutingMethods(); // TODO: In general (although note that we currently execute this method outside the currentProcess != null check to provide a way to stop all activity, however it has been started) there might be methods executing which are not due to the current script, fix this assumption to prevent interrupting too many methods.
		} catch (RuntimeException exception) {
			logger.error("Failed to interrupt currently executing methods.", exception);
		}
		if (currentProcess != null) {
			Process process = currentProcess;
			try {
				logger.info("Terminating the current script.");
				process.destroy();
				Thread.sleep(KILL_TIMEOUT_MILLISECONDS);
			} catch (InterruptedException exception) {
				logger.error("Interrupted whilst terminating the current script.", exception);
				Thread.currentThread().interrupt();
			}
			var future = process.destroyForcibly().onExit();
			try {
				future.get(KILL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				logger.error("Interrupted whilst killing the current script.", exception);
				Thread.currentThread().interrupt();
			} catch (ExecutionException | TimeoutException exception) {
				logger.error("Error killing the current script.", exception);
			} finally {
				currentProcess = null;
			}
		}
	}

	public synchronized void launch(String body, boolean automated, boolean background) {
		try {
			logger.info("Launching script:\n{}\n", body);
			if (!automated) {
				unautomatedLaunchCount++;
			}
			if (!background) {
				terminate();
			}
			var builder = new ProcessBuilder(
				"/usr/bin/python3", "-c", "from foundry_api.script_launcher import bootstrap; exec(bootstrap)", contextDirectory.toString()
			);
			var environment = builder.environment();
			environment.put("PYTHONPATH", contextDirectory.toString());
			builder.directory(new File(System.getProperty("user.home"), "Desktop"));
			Process process = builder.start();
			try (var outputStream = process.getOutputStream(); var outputWriter = new OutputStreamWriter(outputStream)) {
				globalPool.submit(new LineReaderTask(process.getInputStream(), line -> store.appendScriptLog(line)));
				globalPool.submit(new LineReaderTask(process.getErrorStream(), line -> {
					var trimmed = line.trim();
					if (!trimmed.startsWith("File \"<string>\", line ") && !trimmed.endsWith(", in <module>")) { // TODO: Is there a better way to tidy up the tracebacks?
						store.appendScriptLog(line);
					}
				}));
				outputWriter.write(body + "\n\ntime.sleep(2)\n\n"); // Ensure that the script ends with at least one clear newline. // TODO: Properly fix this time.sleep(2) hack, which is there to prevent the domain socket closure from interrupting the dispatch and completion of Daemon methods which are called by the script.
			}
			if (!background) {
				currentProcess = process;
			}
		} catch (RuntimeException | IOException exception) {
			logger.error("Error launching script.", exception);
		}
	}

	public synchronized int getUnautomatedLaunchCount() {
		return unautomatedLaunchCount;
	}

}
