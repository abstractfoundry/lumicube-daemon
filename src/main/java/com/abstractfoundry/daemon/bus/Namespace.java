/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

import com.abstractfoundry.daemon.uavcan.NodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Namespace { // Note: Immutable.

	private static final Logger logger = LoggerFactory.getLogger(Namespace.class);

	public static class Module {

		private final int id;
		private final Metadata metadata;
		private final Map<String, Integer> fields;

		public Module(int id, Metadata metadata, Map<String, Integer> fields) {
			this.id = id;
			this.metadata = metadata;
			this.fields = Collections.unmodifiableMap(fields);
		}

		public int getId() {
			return id;
		}

		public Metadata getMetadata() {
			return metadata;
		}

		public int getKey(String name) {
			Integer key = fields.get(name);
			if (key != null) {
				return key;
			} else {
				throw new NoSuchElementException("No such field: " + name);
			}
		}

		public Set<String> getFields() {
			return fields.keySet();
		}

		public List<String> getSortedFields() {
			var result = new ArrayList<>(fields.keySet());
			result.sort((left, right) -> fields.get(left) - fields.get(right));
			return result;
		}

	}

	public static Namespace EMPTY = new Namespace(
		Map.of()
	);

	private final Map<String, Module> entries;

	private Namespace(Map<String, Module> entries) {
		this.entries = Collections.unmodifiableMap(entries);
	}

	public Module getModule(String name) {
		return entries.get(name);
	}

	public Set<String> getModuleNames() {
		return entries.keySet();
	}

	public static Namespace build(Map<Integer, String> preferredNamesById, Map<Integer, Metadata> metadataById, Map<Integer, NodeInfo> nodeInfoById) { // TODO: Disambiguate conflicting names by appending FNV_1a hash suffix (like the old daemon).
		var idByModule = new HashMap<String, Integer>();
		var fieldsByModule = new HashMap<String, Map<String, Integer>>();
		for (var preferredNameEntry : preferredNamesById.entrySet()) {
			var id = preferredNameEntry.getKey();
			var preferredName = preferredNameEntry.getValue();
			var metadata = metadataById.get(id);
			var info = nodeInfoById.get(id);
			if (preferredName != null && metadata != null) {
				for (var key : metadata.keys()) {
					var module = metadata.module(key);
					var field = metadata.name(key);
					if (module == null) {
						module = preferredName; // Without an explicit module name for the field, we default to the preferred name for the node.
					} else if (info != null && info.getName().equals("com.abstractfoundry.cube") && module.equals("buttons")) { // TODO: Unhack.
						module = "system_button";
					}
					var existingId = idByModule.putIfAbsent(module, id);
					if (existingId != null && !existingId.equals(id)) {
						return EMPTY; // TODO: Rather append an FNV_1a hash suffix to the module name if needed for disambiguation.
					}
					var fields = fieldsByModule.computeIfAbsent(module, ignored -> new HashMap<>());
					var existingKey = fields.putIfAbsent(field, key);
					if (existingKey != null && !existingKey.equals(key)) {
						logger.error("Conflicting field name: {}, {}, {}.", field, existingKey, key);
						return EMPTY;
					}
				}
			}
		}
		var entries = new HashMap<String, Module>();
		for (var idEntry : idByModule.entrySet()) {
			var module = idEntry.getKey();
			var id = idEntry.getValue();
			var fields = fieldsByModule.get(module);
			var metadata = metadataById.get(id); // TODO: Prune the metadata object for this module to consist of only relevant fields (currently it contains all the fields for the entire node).
			entries.put(module,
				new Module(id, metadata, fields)
			);
		}
		return new Namespace(entries);
	}

	public void print() {
		for (var namespaceEntry : entries.entrySet()) {
			var moduleName = namespaceEntry.getKey();
			var module = namespaceEntry.getValue();
			var id = module.id;
			var fields = module.fields;
			for (var moduleEntry : fields.entrySet()) {
				var fieldName = moduleEntry.getKey();
				var key = moduleEntry.getValue();
				logger.info("Field {}.{} -> ({}, {}).", moduleName, fieldName, id, key);
			}
		}
	}

}
