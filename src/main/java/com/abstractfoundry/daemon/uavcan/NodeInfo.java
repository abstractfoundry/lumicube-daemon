/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.uavcan;

import com.abstractfoundry.daemon.common.Builder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class NodeInfo {

	private final Duration uptime;
	private final int health;
	private final int mode;
	private final UUID uuid;
	private final String name;

	private NodeInfo(Duration uptime, int health, int mode, UUID uuid, String name) {
		this.uptime = uptime;
		this.health = health;
		this.mode = mode;
		this.uuid = uuid;
		this.name = name;
	}

	public Duration getUptime() {
		return uptime;
	}

	public int getHealth() {
		return health;
	}

	public int getMode() {
		return mode;
	}

	public UUID getUuid() {
		return uuid;
	}

	public String getName() {
		return name;
	}

	public static NodeInfo deserialise(byte[] buffer, int offset, int length) { // Note: We ignore most of the information provided (see https://dronecan.github.io/Specification/7._List_of_standard_data_types/).
		var seconds = 0L;
		for (var index = 0; index < 4; index++) {
			seconds += (long) buffer[offset + index] << (index * 8); // Note: Cast to prevent signed overflow.
		}
		Duration uptime = Duration.ofSeconds(seconds);
		var health = (buffer[offset + 4] >> 6) & 0x03;
		var mode = (buffer[offset + 4] >> 3) & 0x07;
		var uuid = Builder.uuid(buffer, offset + 24, 16);
		var jump = buffer[offset + 40]; // Length of any certificate.
		var name = new String(buffer, offset + 41 + jump, Math.min(80, length - 41 - jump), StandardCharsets.US_ASCII);
		return new NodeInfo(uptime, health, mode, uuid, name);
	}

}
