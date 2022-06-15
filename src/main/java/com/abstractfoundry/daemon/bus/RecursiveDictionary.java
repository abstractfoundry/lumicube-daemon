/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public class RecursiveDictionary { // Note: Immutable. // TODO: Common up some logic with FlatDictionary into an abstract super class?

	protected final Metadata metadata;
	private final NavigableMap<Integer, Object> fields;

	private RecursiveDictionary(Metadata metadata, NavigableMap<Integer, Object> fields) {
		this.metadata = metadata;
		this.fields = fields;
	}

	public Object get(int key) {
		return fields.get(key);
	}

	public Set<Integer> keys() {
		return Collections.unmodifiableSet(
			fields.keySet()
		);
	}

	public int lastKey() {
		return fields.lastKey();
	}

	public int floor(int key) {
		return fields.floorKey(key);
	}

	public static RecursiveDictionary deserialise(byte[] buffer, int offset, int length, Metadata metadata) {
		var wrapped = ByteBuffer.wrap(buffer, offset, length);
		wrapped.order(ByteOrder.LITTLE_ENDIAN);
		return deserialise(wrapped, metadata);
	}

	public static RecursiveDictionary deserialise(ByteBuffer buffer, Metadata metadata) {
		var fields = new TreeMap<Integer, Object>();
		var keyAccumulator = 0;
		while (buffer.position() < buffer.limit()) {
			var command = buffer.get() & 0xFF;
			var width = command & 0x0F;
			if (width < 0 || width > 4) {
				throw new IllegalStateException("Unsupported width.");
			}
			var discriminator = (command >> 4) & 0x0F;
			var parameter = 0;
			for (int index = 0; index < width; index++) {
				parameter |= (buffer.get() & 0xFF) << 8 * index;
			}
			switch (discriminator) {
				case 0: // Run follows.
					var runLength = parameter;
					var runCountdown = runLength;
					while (runCountdown > 0) {
						var metadataFloor = metadata.floor(keyAccumulator);
						var metadataSpan = metadata.span(metadataFloor);
						var metadataType = metadata.type(metadataFloor);
						var metadataSize = metadata.size(metadataFloor);
						var blockLength = Integer.min(runCountdown, metadataFloor + metadataSpan - keyAccumulator); // Homogeneously typed block of values.
						if (metadataType == FieldType.NULL && metadataSize > 0) { // Fixed-size NULL block.
							for (var index = 0; index < blockLength; index++) {
								fields.put(keyAccumulator + index, null);
								buffer.position(buffer.position() + metadataSize); // Skip over NULL data.
							}
						} else if (metadataType < 6) { // Fixed-/variable-size integral types.
							var metadataSigned = metadataType > 1 && metadataType < 8 && metadataType != 4; // TODO: Factor-out and tabulate this typing information.
							var signExtensionBound = 1 << (8 * metadataSize - 1);
							for (var index = 0; index < blockLength; index++) {
								var thisSize = metadataSize == 0 ? parseSize(buffer) : metadataSize;
								var value = 0;
								for (var shift = 0; shift < thisSize; shift++) {
									value |= (buffer.get() & 0xFF) << 8 * shift;
								}
								if (metadataSigned && value >= signExtensionBound) {
									value -= 2 * signExtensionBound; // Sign-extend.
								}
								fields.put(keyAccumulator + index,
									metadataType != FieldType.BOOLEAN ? value : value != 0
								);
							}
						} else if (metadataType == FieldType.UTF8_STRING && metadataSize == 0) { // Variable size strings.
							for (var index = 0; index < blockLength; index++) {
								var stringSize = parseSize(buffer);
								var string = new String(buffer.array(), buffer.position(), stringSize, StandardCharsets.UTF_8);
								fields.put(keyAccumulator + index, string);
								buffer.position(buffer.position() + stringSize);
							}
						} else if (metadataType == FieldType.DICTIONARY && metadataSize == 0) { // Variable size sub-dictionary.
							var submetadata = metadata.metadata(metadataFloor);
							for (var index = 0; index < blockLength; index++) {
								var subdictionary = RecursiveDictionary.deserialise(buffer, submetadata);
								fields.put(keyAccumulator + index, subdictionary);
							}
						} else {
							throw new IllegalStateException("Unsupported type.");
						}
						runCountdown -= blockLength;
						keyAccumulator += blockLength;
					}
					break;
				case 1: // Skip.
					keyAccumulator += parameter;
					break;
				default:
					throw new IllegalStateException("Invalid dictionary command.");
			}
		}
		if (buffer.position() != buffer.limit()) {
			throw new IllegalStateException("Buffer overflow.");
		}
		return new RecursiveDictionary(metadata, fields);
	}

	public RecursiveDictionary merge(RecursiveDictionary other) { // Note: Recursively merge (values from the second dictionary take precedence).
		if (metadata != other.metadata) {
			throw new IllegalArgumentException("Incompatible metadata.");
		}
		var fields = new TreeMap<Integer, Object>();
		fields.putAll(this.fields);
		for (var entry : other.fields.entrySet()) {
			var key = entry.getKey();
			var value = entry.getValue();
			var existing = fields.putIfAbsent(key, value);
			if (existing != null) {
				Object merged;
				if (existing instanceof RecursiveDictionary && value instanceof RecursiveDictionary) {
					merged = ((RecursiveDictionary) existing).merge(((RecursiveDictionary) value)); // Recursively merge sub-dictionaries.
				} else {
					merged = value; // Other kinds of value are simply "merged" by replacement.
				}
				fields.put(key, merged);
			}
		}
		return new RecursiveDictionary(metadata, fields);
	}

	private static int parseSize(ByteBuffer buffer) {
		var size = 0;
		for (var counter = 0; ; counter++) {
			var value = buffer.get() & 0xFF;
			size |= (value & 0x7F) << (counter * 7);
			if (value < 128) {
				break;
			}
		}
		return size;
	}

}
