/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VirtualMicrophone {

	private static final Logger logger = LoggerFactory.getLogger(VirtualMicrophone.class);

	// TODO: Try a longer / power-of-two latency to reduce PulseAudio CPU load from the virtual microphone.

	public static final String SINK_NAME = "abstract_foundry.daemon.microphone.null_sink";
	public static final String SOURCE_NAME = "abstract_foundry.daemon.microphone.virtual_source";
	
	private final OutputStream stream;

	public VirtualMicrophone() {
		try {
			var sinkLoaded = getPulseAudioSymbolicNames("sinks").contains(SINK_NAME);
			var sourceLoaded = getPulseAudioSymbolicNames("sources").contains(SOURCE_NAME);
			if (!sinkLoaded) {
				sinkLoaded = loadPulseAudioModule("module-null-sink", "sink_name=" + SINK_NAME, "sink_properties=device.description='AbstractFoundryVirtualMicrophoneSink'");
			}
			if (sinkLoaded && !sourceLoaded) {
				sourceLoaded = loadPulseAudioModule("module-remap-source", "master=" + SINK_NAME + ".monitor", "source_name=" + SOURCE_NAME, "source_properties=device.description='AbstractFoundryVirtualMicrophoneSource'");
			}
			if (sinkLoaded && sourceLoaded) {
				var defaultSet = setPulseAudioDefaultSource(SOURCE_NAME);
				if (!defaultSet) {
					throw new IllegalStateException("Error setting virtual microphone as default source.");
				}
				var builder = new ProcessBuilder("/usr/bin/pacat", "--device=" + SINK_NAME, "--format=" + "s16le", "--channels=" + "1", "--rate=" + "16000", "--latency=" + "672", "--client-name=AbstractFoundryDaemon")
					.redirectOutput(ProcessBuilder.Redirect.DISCARD) // Note: Directing the output stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
					.redirectError(ProcessBuilder.Redirect.DISCARD); // Note: Directing the error stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
				var process = builder.start();
				this.stream = process.getOutputStream(); // TODO: Provide a way to close the stream.
			} else {
				throw new IllegalStateException("Error installing virtual microphone source.");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("I/O exception whilst installing the virtual microphone.", exception);
		}
	}

	public void push(byte[] buffer, int offset, int length) throws IOException {
		stream.write(buffer, offset, length);
		stream.flush(); // TODO: We must flush regularly as process.getOutputStream() returns a buffered output stream (of about 8192 bytes, i.e. about 0.25 seconds) but maybe we should flush less regularly and/or increase the --latency parameter above?
	}

	private static boolean setPulseAudioDefaultSource(String name) throws IOException {
		var builder = new ProcessBuilder("/usr/bin/pactl", "set-default-source", name)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD); // Note: Directing the output stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
		var process = builder.start();
		var raw = process.getErrorStream();
		try (var reader = new InputStreamReader(raw); var stream = new BufferedReader(reader)) {
			while (true) {
				var line = stream.readLine();
				if (line == null) {
					break;
				} else if (line.trim().length() > 0) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean loadPulseAudioModule(String ...arguments) throws IOException {
		var command = new ArrayList<String>();
		var prefix = Arrays.asList("/usr/bin/pactl", "load-module");
		var suffix = Arrays.asList(arguments);
		command.addAll(prefix);
		command.addAll(suffix);
		var builder = new ProcessBuilder(command)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD); // Note: Directing the output stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
		var process = builder.start();
		var raw = process.getErrorStream();
		try (var reader = new InputStreamReader(raw); var stream = new BufferedReader(reader)) {
			while (true) {
				var line = stream.readLine();
				if (line == null) {
					break;
				} else if (line.trim().length() > 0) {
					return false;
				}
			}
		}
		return true;
	}

	private static Set<String> getPulseAudioSymbolicNames(String kind) throws IOException {
		var result = new HashSet<String>();
		var builder = new ProcessBuilder("/usr/bin/pactl", "list", "short", kind)
			.redirectError(ProcessBuilder.Redirect.DISCARD); // Note: Directing the error stream ensures we don't deadlock, see: https://stackoverflow.com/a/57949752
		var process = builder.start();
		var raw = process.getInputStream();
		try (var reader = new InputStreamReader(raw); var stream = new BufferedReader(reader)) {
			while (true) {
				var line = stream.readLine();
				if (line == null) {
					break;
				}
				var values = line.split("\t");
				var name = values[1];
				result.add(name);
			}
		}
		return result;
	}

}
