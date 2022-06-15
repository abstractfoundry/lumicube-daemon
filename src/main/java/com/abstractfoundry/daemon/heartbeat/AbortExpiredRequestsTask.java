/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.heartbeat;

import com.abstractfoundry.daemon.uavcan.SerialConnectedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AbortExpiredRequestsTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AbortExpiredRequestsTask.class);

	private final Runtime runtime;
	private final int cadence;
	private final SerialConnectedNode daemonNode;

	private int counter = 0;

	AbortExpiredRequestsTask(SerialConnectedNode daemonNode, int cadence) {
		this.daemonNode = daemonNode;
		this.cadence = cadence;
		this.runtime = Runtime.getRuntime();
	}

	@Override
	public void run() {
		if (counter++ % cadence == 0) {
			var inboxBacklog = daemonNode.inboxBacklog();
			var outboxBacklog = daemonNode.outboxBacklog();
			var collectorBacklog = daemonNode.collectorBacklog();
			var footprint = runtime.totalMemory() - runtime.freeMemory();
			var inFlight = daemonNode.outstandingRequests();
			var timedOut = daemonNode.abortExpiredRequests();
			LoggingMethod method = timedOut > 0 ? logger::warn : logger::debug;
			method.log("Inbox backlog = {}; Outbox backlog = {}; Collector backlog = {}.", inboxBacklog, outboxBacklog, collectorBacklog);
			method.log("Footprint = {} kilobytes.", footprint / 1000);
			method.log("In-flight = {}.", inFlight);
			method.log("Timed-out = {}.", timedOut);
		}
	}

	private static interface LoggingMethod {
		public void log(String string, Object... objects);
	}

}
