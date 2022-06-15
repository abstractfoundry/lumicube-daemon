/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.common;

import redis.clients.jedis.exceptions.JedisException;

public class DatabaseException extends RuntimeException {

		public DatabaseException(String message, JedisException cause) {
			super(message, cause);
		}

}
