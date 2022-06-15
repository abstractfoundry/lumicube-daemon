/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.representation;

import java.util.Map;

public class FieldsRepresentation {

	private Map<String, Object> values;
	private MetadataRepresentation metadata;

	public Map<String, Object> getValues() {
		return values;
	}

	public void setValues(Map<String, Object> values) {
		this.values = values;
	}

	public MetadataRepresentation getMetadata() {
		return metadata;
	}

	public void setMetadata(MetadataRepresentation metadata) {
		this.metadata = metadata;
	}

}
