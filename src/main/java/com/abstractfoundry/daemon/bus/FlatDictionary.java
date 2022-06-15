/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlatDictionary { // TODO: Common up some logic with RecursiveDictionary into an abstract super class?

	private static final Logger logger = LoggerFactory.getLogger(FlatDictionary.class);

	public static int serialise(byte[] buffer, int cursor, int[] keys, int[] values, int offset, int length, Metadata metadata) {
		if (keys.length != values.length) {
			throw new IllegalArgumentException("Mismatched arrays.");
		} else if (offset < 0 || length < 0 || offset + length > keys.length) {
			throw new IllegalArgumentException("Invalid offset or length.");
		}
		var keyAccumulator = 0;
		var fieldsRemaining = length;
		while (fieldsRemaining > 0) {
			// (1) Delimit the run of contiguous keys.
			var runStart = offset + length - fieldsRemaining;
			var scanPointer = runStart;
			do {
				scanPointer++;
			} while (scanPointer < offset + length && keys[scanPointer] == keys[scanPointer - 1] + 1);
			var runEnd = scanPointer;
			// (2) Insert the skip word (the only legitimate zero skip is at the start of the dictionary).
			var skipValue = keys[runStart] - keyAccumulator;
			if (skipValue < 0) {
				throw new IllegalStateException("Unordered fields.");
			} else if (skipValue == 0 && fieldsRemaining != length) {
				throw new IllegalArgumentException("Delimitation error.");
			} else if (skipValue > 0) {
				var width = 4 - (Integer.numberOfLeadingZeros(skipValue) / 8);
				var command = (byte) (0x10 | width);
				buffer[cursor++] = command;
				for (int index = 0; index < width; index++) {
					buffer[cursor++] = (byte) ((skipValue >> 8 * index) & 0xFF);
				}
			}
			keyAccumulator += skipValue;
			// (3) Insert the run length.
			var runLength = runEnd - runStart;
			if (runLength <= 0) {
				throw new IllegalStateException("Delimitation error.");
			} else {
				var width = 4 - (Integer.numberOfLeadingZeros(runLength) / 8);
				var command = (byte) width;
				buffer[cursor++] = command;
				for (int index = 0; index < width; index++) {
					buffer[cursor++] = (byte) ((runLength >> 8 * index) & 0xFF);
				}
			}
			// (4) Serialise the run.
			var runCountdown = runLength;
			while (runCountdown > 0) {
				var blockStart = runEnd - runCountdown;
				var firstKey = keys[blockStart];
				var metadataFloor = metadata.floor(firstKey);
				var metadataSpan = metadata.span(metadataFloor);
				var metadataType = metadata.type(metadataFloor);
				var metadataSize = metadata.size(metadataFloor);
				var blockLength = Integer.min(runCountdown, metadataFloor + metadataSpan - firstKey); // Homogeneously typed block of values.
				if (metadataType > 0 && metadataType < 6 && metadataSize > 0) { // Fixed-size integral types.
					for (var index = 0; index < blockLength; index++) {
						var value = values[blockStart + index];
						for (var shift = 0; shift < metadataSize; shift++) {
							buffer[cursor++] = (byte) ((value >> 8 * shift) & 0xFF);
						}
					}
				} else if (metadataType == 8 && metadataSize == 1) { // UTF_8 characters.
					for (var index = 0; index < blockLength; index++) {
						buffer[cursor++] = (byte) (values[blockStart + index] & 0xFF);
					}
				} else {
					throw new IllegalStateException("Unsupported type.");
				}
				runCountdown -= blockLength;
				keyAccumulator += blockLength;
				fieldsRemaining -= blockLength;
			}
		}
		return cursor;
	}

	public static int deserialise(byte[] buffer, int offset, int length, int[] keys, int[] values, int cursor, Metadata metadata) {
		if (keys.length != values.length) {
			throw new IllegalArgumentException("Mismatched arrays.");
		} else if (offset < 0 || length < 0 || offset + length > buffer.length) {
			throw new IllegalArgumentException("Invalid offset or length.");
		}
		var limit = offset + length;
		var keyAccumulator = 0;
		while (offset < limit) {
			var command = buffer[offset++] & 0xFF;
			var width = command & 0x0F;
			if (width < 0 || width > 4) {
				throw new IllegalStateException("Unsupported width.");
			}
			var discriminator = (command >> 4) & 0x0F;
			var parameter = 0;
			for (int index = 0; index < width; index++) {
				parameter |= (buffer[offset++] & 0xFF) << 8 * index;
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
						var isSigned = metadataType > 1 && metadataType < 8 && metadataType != 4;
						var blockLength = Integer.min(runCountdown, metadataFloor + metadataSpan - keyAccumulator); // Homogeneously typed block of values.
						if (metadataType > 0 && metadataType < 6 && metadataSize > 0) { // Fixed-size integral types.
							var signExtensionBound = 1 << (8 * metadataSize - 1);
							for (var index = 0; index < blockLength; index++) {
								var value = 0;
								for (var shift = 0; shift < metadataSize; shift++) {
									value |= (buffer[offset++] & 0xFF) << 8 * shift;
								}
								if (isSigned && value >= signExtensionBound) {
									value -= 2 * signExtensionBound; // Sign-extend.
								}
								keys[cursor] = keyAccumulator + index;
								values[cursor] = value;
								cursor++;
							}
						} else if (metadataType == 6 && metadataSize == 4) { // Single-precision floating-point type.
							for (var index = 0; index < blockLength; index++) {
								var raw = 0;
								for (var shift = 0; shift < metadataSize; shift++) {
									raw |= (buffer[offset++] & 0xFF) << 8 * shift;
								}
								var value = Float.intBitsToFloat(raw);
								keys[cursor] = keyAccumulator + index;
								values[cursor] = Math.round(value); // TODO: Do not round floating-point values to the nearest integer.
								cursor++;
							}
						} else if (metadataType == 8 && metadataSize == 1) { // UTF-8 strings.
							offset += blockLength; // TODO: We currently skip over strings, fix this.
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
		if (offset != limit) {
			throw new IllegalStateException("Buffer overflow.");
		}
		return cursor;
	}

}
