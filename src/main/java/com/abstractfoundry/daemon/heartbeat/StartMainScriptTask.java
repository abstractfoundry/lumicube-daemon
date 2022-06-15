/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;

public class StartMainScriptTask implements Runnable { // TODO: Make this less hacky (e.g. analyse dependent modules of script rather than using a timer).

	private static final long DELAY_MILLISECONDS = 20_000L;

	private final ScriptExecutor scriptExecutor;
	private final Store store;

	private boolean timerStarted = false;
	private boolean taskComplete = false;
	private long timerTrigger = 0L;

	StartMainScriptTask(ScriptExecutor scriptExecutor, Store store) {
		this.scriptExecutor = scriptExecutor;
		this.store = store;
	}

	@Override
	public void run() {
		if (!timerStarted) {
			var module = store.getNamespace().getModule("display");
			if (module != null) {
				timerTrigger = System.currentTimeMillis() + DELAY_MILLISECONDS;
				timerStarted = true;
			}
		}
		if (timerStarted && !taskComplete) {
			if (System.currentTimeMillis() > timerTrigger) {
				taskComplete = true; // Our task is complete even if we decide to not launch anything.
				var body = store.getScriptBody("main", null);
				if (body != null && scriptExecutor.getUnautomatedLaunchCount() == 0) { // Only launch the main script if the user has not already launched something.
					scriptExecutor.launch(body, true);
				}
			}
		}
	}

}
