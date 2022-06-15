/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.server.method.node;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.server.MethodJsonParser;
import com.abstractfoundry.daemon.uavcan.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetFieldsMethod { // TODO: Abstract common logic with DisplaySetMethod.

	private static final Logger logger = LoggerFactory.getLogger(GetFieldsMethod.class);
	
	private final Node daemonNode;
	private final Store store;
	private final ObjectMapper serialiser = new ObjectMapper();

	private String moduleName; // The module context within which to parse and issue the request.
	private final Parser parser = new Parser();

	public GetFieldsMethod(Node daemonNode, Store store) {
		this.daemonNode = daemonNode;
		this.store = store;
	}

	public void parse(String moduleName, CharSequence data) {
		this.moduleName = moduleName;
		parser.parse(data);
	}

	public boolean invoke(Consumer<CharSequence> responder) {
		var dictionary = new HashMap(); // TODO: Reuse and clear object instead?
		try {
			var module = store.getNamespace().getModule(moduleName);
			if (module != null) {
				var fields = new HashMap<String, Object>(); // TODO: Reuse and clear object instead?
				store.dumpLatestFields(module, fields);
				dictionary.put("status", 0);
				dictionary.put("result", fields);
			} else {
				dictionary.put("status", -1);
				dictionary.put("error", "Module '" + moduleName + "' is unavailable."); // TODO: Perhaps in this case, and others like it, we should instead wait until the module becomes available (for at least something like 30 seconds).
			}
		} catch (RuntimeException exception) {
			dictionary.put("status", -1);
			dictionary.put("error", exception.getMessage());
		}
		String result;
		try {
			result = serialiser.writeValueAsString(dictionary); // TODO: Avoid creating garbage by writing to a ByteArrayOutputStream?
		} catch (IOException exception) {
			throw new IllegalStateException("Error serialising the result.", exception);
		}
		responder.accept(result);
		return true;
	}

	private class Parser extends MethodJsonParser {

		@Override
		protected void clear() {

		}

		@Override
		protected void argument(int index) {
			throw new RuntimeException("Unexpected argument: '" + index + "'.");
		}

		@Override
		protected void argument(CharSequence name) {
			throw new RuntimeException("Unexpected argument: '" + name + "'.");
		}

	}

}
