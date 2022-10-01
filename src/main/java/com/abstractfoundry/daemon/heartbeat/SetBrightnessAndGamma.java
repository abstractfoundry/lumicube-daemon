/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;

public class SetBrightnessAndGamma implements Runnable {

	private final String SCRIPT = "display.gamma_correction_green = 150\n" +
"display.gamma_correction_red = 255\n" +
"display.gamma_correction_blue = 130\n" +
"display.brightness = 65\n";
	
	private final ScriptExecutor scriptExecutor;
	private final Store store;

	private boolean done = false;

	SetBrightnessAndGamma(ScriptExecutor scriptExecutor, Store store) {
		this.scriptExecutor = scriptExecutor;
		this.store = store;
	}

	@Override
	public void run() {
		if (!done) {
			var module = store.getNamespace().getModule("display");
			if (module != null) {
				scriptExecutor.launch(SCRIPT, true, true);
				done = true;
			}
		}
	}

}
