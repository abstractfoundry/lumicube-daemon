/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;

public class DisplayNetworkAddress implements Runnable {

	private final String WRITE_SCREEN_SCRIPT = "screen.draw_rectangle(0, 0, 320, 240, black)\n" +
"text = \"IP address: \" + pi.ip_address()\n" +
"screen.write_text(10, 18, text, 1, white, black)";

	private final String SCROLL_LEDS_SCRIPT = "display.set_all(black)\n" +
"while True:\n" +
"    ip_address = pi.ip_address()\n" +
"    text = ip_address if ip_address.strip() else 'NO WI-FI'\n" +
"    display.scroll_text(text, orange, speed=0.6)\n" +
"\n";
	
	private final ScriptExecutor scriptExecutor;
	private final Store store;

	private boolean addressWrittenToScreen = false;
	private int systemButtonCachedId, systemButtonCachedKey, systemButtonPreviousCount;
	private boolean systemButtonAvailable = false;

	DisplayNetworkAddress(ScriptExecutor scriptExecutor, Store store) {
		this.scriptExecutor = scriptExecutor;
		this.store = store;
	}

	@Override
	public void run() {
		if (!addressWrittenToScreen) {
			var module = store.getNamespace().getModule("screen");
			if (module != null) {
				scriptExecutor.launch(WRITE_SCREEN_SCRIPT, true, true);
				addressWrittenToScreen = true;
			}
		}
		if (!systemButtonAvailable) {
			var module = store.getNamespace().getModule("system_button");
			if (module != null) {
				systemButtonCachedId = module.getId();
				systemButtonCachedKey = module.getKey("button_pressed_count");
				Integer value = (Integer) store.getLatestField(systemButtonCachedId, systemButtonCachedKey);
				if (value != null) {
					systemButtonPreviousCount = value;
					systemButtonAvailable = true;
				}
			}
		}
		if (systemButtonAvailable) {
			Integer value = (Integer) store.getLatestField(systemButtonCachedId, systemButtonCachedKey);
			if (value != null) {
				if (!value.equals(systemButtonPreviousCount)) {
					systemButtonPreviousCount = value;
					scriptExecutor.launch(SCROLL_LEDS_SCRIPT, false, false);
				}
			}
		}
	}

}
