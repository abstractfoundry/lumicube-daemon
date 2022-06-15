/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.representation;

import java.util.List;

public class MetadataRepresentation {

	private List<String> order;
	private List<String> spanning;
	private List<String> nonpublic;

	public List<String> getOrder() {
		return order;
	}

	public void setOrder(List<String> order) {
		this.order = order;
	}

	public List<String> getSpanning() {
		return spanning;
	}

	public void setSpanning(List<String> spanning) {
		this.spanning = spanning;
	}

	public List<String> getNonpublic() {
		return nonpublic;
	}

	public void setNonpublic(List<String> nonpublic) {
		this.nonpublic = nonpublic;
	}

}
