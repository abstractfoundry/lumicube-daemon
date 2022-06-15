/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueriedMetadata implements Metadata { // Note: Immutable.

	private static final Logger logger = LoggerFactory.getLogger(QueriedMetadata.class);
	
	private final RecursiveDictionary dictionary;
	
	public QueriedMetadata(RecursiveDictionary dictionary) {
		this.dictionary = dictionary;
	}

	@Override
	public String name(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		return (String) subdictionary.get(BootstrapMetadata.NAME_KEY);
	}

	@Override
	public int type(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		return (int) subdictionary.get(BootstrapMetadata.TYPE_KEY);
	}

	@Override
	public int size(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		return (int) subdictionary.get(BootstrapMetadata.SIZE_KEY);
	}

	@Override
	public int span(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		return (int) subdictionary.get(BootstrapMetadata.SPAN_KEY);
	}

	@Override
	public boolean debug(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		var result = subdictionary.get(BootstrapMetadata.DEBUG_KEY); // Non-mandatory field, could return NULL.
		return result != null ? (boolean) result: false;
	}

	@Override
	public boolean system(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		var result = subdictionary.get(BootstrapMetadata.SYSTEM_KEY); // Non-mandatory field, could return NULL.
		return result != null ? (boolean) result: false;
	}

	@Override
	public String module(int key) {
		var subdictionary = (RecursiveDictionary) dictionary.get(key);
		return (String) subdictionary.get(BootstrapMetadata.MODULE_KEY);
	}

	@Override
	public int floor(int key) {
		return dictionary.floor(key);
	}

	@Override
	public Metadata metadata(int key) {
		throw new UnsupportedOperationException("Unsupported operation."); // Note: To support nested dictionary metadata we would need to add another boostrap key after MODULE_KEY, e.g. METADATA_KEY.
	}

	@Override
	public Set<Integer> keys() {
		return dictionary.keys();
	}

}
