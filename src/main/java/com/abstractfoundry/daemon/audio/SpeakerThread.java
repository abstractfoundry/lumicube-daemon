/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.audio;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.FlatDictionary;
import com.abstractfoundry.daemon.bus.Metadata;
import com.abstractfoundry.daemon.uavcan.BackoffException;
import com.abstractfoundry.daemon.uavcan.Node;
import com.abstractfoundry.daemon.uavcan.NullContinuation;
import com.abstractfoundry.daemon.uavcan.TypeId;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpeakerThread extends Thread {

	private static final Logger logger = LoggerFactory.getLogger(SpeakerThread.class);

	// TODO: We should really create a new output device inside PulseAudio, rather than monitoring the default, either making it the default, or at least having out methods target our device explicitly by name.

	private static final int BATCH_CAPACITY = 112; // TODO: Eventually we should take this parameter from the metadata.
	private static final int BATCH_COUNT = 3;
	private static final int VOLUME_REDUCTION = 1; // TODO: Set back to 1.

	private final Node daemonNode;
	private final Store store;
	private final int[] keys = new int[BATCH_CAPACITY];
	private final int[] samples = new int[BATCH_CAPACITY];
	private final ByteBuffer scratchpad = ByteBuffer.allocate(BATCH_CAPACITY * BATCH_COUNT * 2); // Format "s16le" uses two bytes of data per sample.
	private final ByteBuffer[] buffers = new ByteBuffer[BATCH_COUNT];

	private boolean emit = false;
	private int destinationId;
	private Metadata cachedMetadata = null;
	private int floorKey = -1;

	public SpeakerThread(Node daemonNode, Store store) {
		super("Foundry Speaker");
		this.daemonNode = daemonNode;
		this.store = store;
		scratchpad.order(ByteOrder.LITTLE_ENDIAN); // Format "s16le" use little-endian byte order.
		for (var index = 0; index < BATCH_COUNT; index++) {
			buffers[index] = ByteBuffer.allocate(256);
			buffers[index].order(ByteOrder.LITTLE_ENDIAN);
		}
	}

	@Override
	public void run() {
		try {
			LockSupport.parkNanos(2_000_000_000L); // Wait until the daemon has warmed-up a bit.
			var defaultSinkDeviceName = getPulseAudioDefaultSinkName();
			if (defaultSinkDeviceName != null) {
				var defaultSinkMonitorDeviceName = defaultSinkDeviceName + ".monitor";
				logger.info("Monitoring audio sink: " + defaultSinkMonitorDeviceName);
				monitorPulseAudioDevice(defaultSinkMonitorDeviceName, "s16le", 1, 32000, scratchpad.capacity());
			}
		} catch (IOException exception) {
			logger.error("I/O exception in speaker thread.", exception);
		}
	}

	private void monitorPulseAudioDevice(String name, String format, int channels, int rate, int latency) throws IOException {
		var builder = new ProcessBuilder("/usr/bin/pamon", "--device=" + name, "--format=" + format, "--channels=" + channels, "--rate=" + rate, "--latency=" + latency, "--client-name=AbstractFoundryDaemon")
			.redirectError(ProcessBuilder.Redirect.DISCARD); // Note: Directing the error stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
		var process = builder.start();
		var raw = process.getInputStream();
		var continuation = new NullContinuation();
		try (var stream = new BufferedInputStream(raw)) {
			while (!Thread.interrupted()) {
				// (1) Read the raw data into the scratchpad.
				var read = scratchpad.capacity();
				stream.readNBytes(scratchpad.array(), 0, read);
				scratchpad.limit(read);
				scratchpad.position(0);
				if (!emit) {
					// (2) Attempt to fetch and cache the destination, metadata etc.
					var module = store.getNamespace().getModule("speaker");
					if (module != null) {
						destinationId = module.getId();
						cachedMetadata = module.getMetadata();
						floorKey = module.getKey("data");
						emit = true;
					} else {
						LockSupport.parkNanos(1_000_000_000L); // Wait for the speaker to become available.
					}
				}
				if (emit) {
					// (3) Serialise the batches.
					for (var batchNumber = 0; batchNumber < BATCH_COUNT; batchNumber++) {
						for (var index = 0; index < samples.length; index++) {
							keys[index] = index + floorKey;
							samples[index] = scratchpad.getShort() / VOLUME_REDUCTION;
						}
						var length = FlatDictionary.serialise(buffers[batchNumber].array(), 0, keys, samples, 0, samples.length, cachedMetadata);
						buffers[batchNumber].limit(length);
						buffers[batchNumber].position(0);
					}
					// (4) Make the request.
					final int requestPriority = 20; // TODO: Make configurable.
					try {
						daemonNode.request(destinationId, TypeId.SET_FIELDS, requestPriority, buffers, BATCH_COUNT, continuation);
					} catch (BackoffException exception) {
						logger.debug("Audio samples skipped due to backoff signal.");
					}
				}
			}
		}
	}

	private String getPulseAudioDefaultSinkName() throws IOException {
		var builder = new ProcessBuilder("/usr/bin/pactl", "info")
			.redirectError(ProcessBuilder.Redirect.DISCARD); // Note: Directing the error stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
		var environment = builder.environment();
		environment.put("LC_ALL", "C");
		environment.put("LANG", "C");
		environment.put("LANGUAGE", "C");
		var process = builder.start();
		var raw = process.getInputStream();
		try (var reader = new InputStreamReader(raw); var stream = new BufferedReader(reader)) {
			while (!Thread.interrupted()) {
				var line = stream.readLine();
				if (line == null) {
					break;
				}
				var pattern = Pattern.compile("DEFAULT SINK:", Pattern.CASE_INSENSITIVE);
				var matcher = pattern.matcher(line);
				while (matcher.find()) {
					var end = matcher.end(0);
					var result = line.substring(end).trim();
					return result;
				}
			}
		}
		return null;
	}

}
