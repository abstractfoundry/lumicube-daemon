/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LineReaderTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(LineReaderTask.class);

	private final InputStream inputStream;
	private final Consumer<String> lineConsumer;

	public LineReaderTask(InputStream inputStream, Consumer<String> lineConsumer) {
		this.inputStream = inputStream;
		this.lineConsumer = lineConsumer;
	}

	@Override
	public void run() {
		try (var inputReader = new InputStreamReader(inputStream); var inputBuffer = new BufferedReader(inputReader)) {
			String line;
			while ((line = inputBuffer.readLine()) != null) {
				lineConsumer.accept(line);
			}
		} catch (RuntimeException | IOException exception) {
			logger.error("Error consuming stream.", exception);
		} finally {
			try {
				inputStream.close();
			} catch (RuntimeException | IOException exception) {
			logger.error("Error closing stream.", exception);
			}
		}
	}

}