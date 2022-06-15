/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.utility;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.audio.VirtualMicrophone;
import com.abstractfoundry.daemon.bus.FlatDictionary;
import com.abstractfoundry.daemon.bus.Metadata;
import com.abstractfoundry.daemon.uavcan.TypeId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BroadcastHandler { // TODO: This class is fairly hacked-up, implement it properly as a (synchronised?) handler for published fields.

	private static final Logger logger = LoggerFactory.getLogger(BroadcastHandler.class);

	private final Store store;
	private final VirtualMicrophone virtualMicrophone;
	private final int[] keys = new int[256];
	private final int[] values = new int[256];
	private final ByteBuffer samples = ByteBuffer.allocate(256);
	private final Map<Integer, Metadata> cachedMetadataById = new HashMap<>(); // Note: Should only be accessed from the inbox disruptor thread.
	private int microphoneKey = -1; // Note: Should only be accessed from the inbox disruptor thread.

	public BroadcastHandler(Store store, VirtualMicrophone virtualMicrophone) {
		this.store = store;
		this.virtualMicrophone = virtualMicrophone;
	}

	public void handle(int sourceId, int typeId, int transferId, int priority, byte[] buffer, int offset, int length) { // Note: Do not capture the buffer, called from the inbox disruptor.
		switch (typeId) {
			case TypeId.NODE_STATUS: // TODO: Update allocator with already allocated IDs.
				store.addConnectedId(sourceId); // TODO: Periodically remove nodes which have dropped offline for more than 5 seconds.
				break;
			case TypeId.PUBLISHED_FIELDS:
				// TODO: WE MUST COPY THE BUFFER IF WE DECIDE TO OFFLOAD PROCESSING TO THE GLOBAL POOL.
				var metadata = cachedMetadataById.get(sourceId);
				if (metadata == null) {
					metadata = store.getMetadata(sourceId);
					cachedMetadataById.put(sourceId, metadata);
				}
				if (microphoneKey < 0) {
					var namespace = store.getNamespace();
					var module = namespace.getModule("microphone");
					if (module != null) {
						microphoneKey = (int) module.getKey("data");
					}
				}
				if (metadata != null && microphoneKey >= 0) { // Don't try to process the data unless we can determine whether or not it is microphone data. // TODO: Unhack this.
					int count = 0;
					try {
						count = FlatDictionary.deserialise(buffer, offset, length, keys, values, 0, metadata);
					} catch (RuntimeException exception) {
						logger.error("Failed to deserialise the publication.", exception);
					}
					if (count == 112 && keys[0] == microphoneKey) { // TODO: UNHACK (and note we should not assume that any publication ONLY contains microhphone data).
						try {
							samples.clear();
							samples.order(ByteOrder.LITTLE_ENDIAN); // The virtual microhone consumes "s16le" samples.
							for (var index = 0; index < count; index++) {
								var sample = (short) values[index];
								samples.putShort(sample);
							}
							virtualMicrophone.push(samples.array(), 0, samples.position()); // TODO: This does blocking I/O, to prevent holding up the inbox disruptor copy the buffer and offload to the global pool.
						} catch (IOException exception) {
							logger.error("Error pushing data to virtual microphone.", exception);
						}
					} else {
						store.putLatestFields(sourceId, keys, values, count);
					}
				}
				break;
		}
	}

}
