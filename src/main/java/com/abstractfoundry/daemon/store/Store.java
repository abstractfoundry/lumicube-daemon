/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.store;

import com.abstractfoundry.daemon.bus.Metadata;
import com.abstractfoundry.daemon.bus.Namespace;
import com.abstractfoundry.daemon.common.DatabaseException;
import com.abstractfoundry.daemon.uavcan.NodeInfo;
import com.redislabs.redistimeseries.RedisTimeSeries;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

public class Store { // TODO: This assumes (correctly, for now) that node IDs are not dynamically reused, but it would be more correct to store the data by UUID.

	private static final Logger logger = LoggerFactory.getLogger(Store.class);

	// TODO: Use finer-grained synchronisation?

	private final JedisPool pool;
	private final RedisTimeSeries timeseries;

	private Namespace namespace = Namespace.EMPTY;
	private final Set<Integer> connectedIds = new HashSet<>();
	private final Map<Integer, String> preferredNamesById = new HashMap<>();
	private final Map<Integer, Metadata> metadataById = new HashMap<>();
	private final Map<Integer, NodeInfo> nodeInfoById = new HashMap<>();
	private final AtomicBoolean upgradeInProgress = new AtomicBoolean();
	private final Map<Integer, Map<Integer, Object>> latestFieldsById = new HashMap<>();
	private final Queue<String> scriptLog = new LinkedList<>();

	public Store(String host, int port) {
		// Note: Configuration adapted from the JRedisTimeSeries defaults.
		JedisPoolConfig config = new JedisPoolConfig();
		config.setMaxTotal(32);
		config.setTestOnBorrow(false);
		config.setTestOnReturn(false);
		config.setTestOnCreate(false);
		config.setTestWhileIdle(false);
		config.setNumTestsPerEvictionRun(-1);
		config.setFairness(true);
		pool = new JedisPool(config, host, port); // TODO: Close Redis resources.
		timeseries = new RedisTimeSeries(pool); // TODO: Close Redis resources.
	}

	private Jedis borrowConnection() { // Note: Ensure individual instances from the pool are accessed from only one thread concurrently.
		return pool.getResource();
	}

	private RedisTimeSeries getTimeseries() { // Note: RedisTimeSeries however appears to handle pool instances in a threadsafe manner.
		return timeseries;
	}

	public void persist() {
		try (var jedis = borrowConnection()) {
			jedis.bgsave();
		}
	}

	public synchronized void addConnectedId(int id) {
		connectedIds.add(id); // TODO: Periodically remove IDs which have dropped offline.
	}

	public synchronized Set<Integer> getConnectedIds() {
		var copy = new HashSet<Integer>(connectedIds);
		return Collections.unmodifiableSet(copy);
	}

	public synchronized Namespace getNamespace() {
		return namespace;
	}

	public synchronized String getPreferredName(int id) {
		return preferredNamesById.get(id);
	}

	public synchronized void putPreferredName(int id, String name) {
		preferredNamesById.put(id, name);
		updateNamespace();
	}

	public synchronized Metadata getMetadata(int id) {
		return metadataById.get(id);
	}

	public synchronized void putMetadata(int id, Metadata metadata) {
		metadataById.put(id, metadata);
		updateNamespace();
	}

	public synchronized NodeInfo getNodeInfo(int id) {
		return nodeInfoById.get(id);
	}

	public synchronized void putNodeInfo(int id, NodeInfo info) {
		nodeInfoById.put(id, info);
		updateNamespace();
	}

	public synchronized void updateNamespace() {
		namespace = Namespace.build(preferredNamesById, metadataById, nodeInfoById);
		namespace.print();
	}

	public synchronized UUID getUuid(int id) {
		var info = getNodeInfo(id);
		return info != null ? info.getUuid() : null;
	}

	public String getScriptBody(String name, String otherwise) {
		try (var jedis = borrowConnection()) {
			var key = buildScriptBodyKey(name);
			var body = jedis.get(key);
			return body != null ? body : otherwise;
		}
	}

	public void putScriptBody(String name, String body) {
		logger.info("Updating script:\n{}\n", body); // Note: Additionally logging the script ensures that errant saves don't completely destroy the previous script, and can be manually recovered if necessary.
		try (var jedis = borrowConnection()) {
			var key = buildScriptBodyKey(name);
			jedis.set(key, body);
			jedis.bgsave();
		}
	}

	public boolean setUpgradeInProgress(boolean value) {
		return upgradeInProgress.getAndSet(value);
	}

	public synchronized void clearScriptLog() {
		scriptLog.clear();
	}

	public synchronized String getScriptLog() {
		var builder = new StringBuilder();
		for (var line : scriptLog) {
			builder.append(line);
			builder.append('\n');
		}
		return builder.toString();
	}

	public synchronized void appendScriptLog(String text) {
		var lines = text.split("\\r?\\n");
		for (var line : lines) {
			while (scriptLog.size() >= 8192) {
				scriptLog.remove();
			}
			scriptLog.add(line);
		}
	}

	public synchronized Object getLatestField(int id, int key) {
		var latest = latestFieldsById.get(id);
		if (latest != null) {
			return latest.get(key);
		} else {
			return null;
		}
	}

	public synchronized void putLatestFields(int id, int[] keys, int[] values, int count) {
		var uuid = getUuid(id);
		var latest = latestFieldsById.computeIfAbsent(id, ignored -> new HashMap<>());
		for (var index = 0; index < count; index++) {
			var key = keys[index];
			var value = values[index];
			latest.put(key, value);
			if (uuid != null) {
				try {
					getTimeseries().add(buildTimeSeriesKey(uuid, key), value); // TODO: Do asychronously to avoid delaying the inbox thread?
				} catch (JedisException exception) {
					throw new DatabaseException("Failed to persist latest field value.", exception);
				}
			}
		}
	}

	public synchronized int dumpFieldTimeseries(Namespace.Module module, int key, long from, long[] timestamps, double[] values) {
		var uuid = getUuid(module.getId());
		try (var jedis = borrowConnection()) {
			var timeseriesKey = buildTimeSeriesKey(uuid, key);
			if (jedis.exists(timeseriesKey)) {
				var range = getTimeseries().range(timeseriesKey, from, Long.MAX_VALUE);
				var length = range.length;
				if (timestamps.length < length || values.length < length) {
					return -1; // TODO: Better error handling.
				}
				for (var index = 0; index < length; index++) {
					timestamps[index] = range[index].getTime();
					values[index] = range[index].getValue();
				}
				return length;
			} else {
				return 0;
			}
		}
	}

	public synchronized void dumpLatestFields(Namespace.Module module, Map<String, Object> result) {
		var id = module.getId();
		var latest = latestFieldsById.get(id);
		if (latest != null) {
			var fields = module.getFields(); // TODO: Support suffixes due to spanning fields.
			for (var field : fields) {
				var key = module.getKey(field);
				var value = latest.get(key);
				result.put(field, value);
			}
		}
	}

	private static String buildScriptBodyKey(String name) {
		return "scripts:body:" + name;
	}

	private static String buildTimeSeriesKey(UUID uuid, int key) {
		return "modules:" + uuid.toString() + ":fields:" + Integer.toString(key);
	}

}
