/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.resource;

import com.abstractfoundry.daemon.script.ScriptExecutor;
import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.rest.representation.PythonScript;
import com.abstractfoundry.daemon.rest.representation.PythonScriptLog;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/scripts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScriptsResource {

	private static final Logger logger = LoggerFactory.getLogger(ScriptsResource.class);

	// TODO: On error return JSON { "error": ... }.

	@GET
	@Path("/{scriptName}")
	public Response getScript(@Context Store store, @PathParam("scriptName") String scriptName) {
		if (scriptName == null) {
			throw new BadRequestException("Script name is mandatory.");
		}
		var body = store.getScriptBody(scriptName, null);
		if (body != null) {
			var result = new PythonScript();
			result.setBody(body);
			return Response.ok(result).build();
		} else {
			throw new NotFoundException("Script does not exist.");
		}
	}

	@POST
	@Path("/{scriptName}")
	public Response postScript(@Context Store store, @PathParam("scriptName") String scriptName, PythonScript script) {
		if (scriptName == null) {
			throw new BadRequestException("Script name is mandatory.");
		} else if (!scriptName.equals("main")) {
			throw new NotFoundException("Only one main script is currently supported.");
		}
		var body = script.getBody();
		store.putScriptBody(scriptName, body);
		return Response.ok().build();
	}

	@GET
	@Path("/{scriptName}/log")
	public Response getScriptLog(@Context Store store, @PathParam("scriptName") String scriptName) {
		if (scriptName == null) {
			throw new BadRequestException("Script name is mandatory.");
		} else if (!scriptName.equals("main")) {
			throw new NotFoundException("Only one main script is currently supported.");
		}
		var text = store.getScriptLog();
		var result = new PythonScriptLog();
		result.setText(text);
		return Response.ok(result).build();
	}
	
	@POST
	@Path("/{scriptName}/methods/start")
	public Response startScript(@Context Store store, @Context ScriptExecutor scriptExecutor, @PathParam("scriptName") String scriptName, PythonScript script) {
		if (scriptName == null) {
			throw new BadRequestException("Script name is mandatory.");
		} else if (!scriptName.equals("main")) {
			throw new NotFoundException("Only one main script is currently supported.");
		}
		String body = null;
		store.clearScriptLog(); // Note: We do this here, so we can prepend any errors saving the script before the messages logged by the script itself.
		if (script != null) {
			body = script.getBody();
			try {
				store.putScriptBody(scriptName, body);
			} catch (RuntimeException exception) {
				logger.warn("Failed to save script.", exception); // TODO: Try again? Putting the script body could fail if Redis is already performing a background save.
				store.appendScriptLog("Error: Failed to save script.");
			}
		} else {
			body = store.getScriptBody(scriptName, "");
		}
		scriptExecutor.launch(body, false, false);
		return Response.ok().build();
	}

	@POST
	@Path("/{scriptName}/methods/stop")
	public Response stopScript(@Context Store store, @Context ScriptExecutor scriptExecutor, @PathParam("scriptName") String scriptName) {
		if (!scriptName.equals("main")) {
			throw new NotFoundException("Only one main script is currently supported.");
		}
		scriptExecutor.terminate();
		return Response.ok().build();
	}

}
