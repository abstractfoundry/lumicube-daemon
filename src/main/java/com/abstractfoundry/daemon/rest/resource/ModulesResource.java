/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.resource;

import com.abstractfoundry.daemon.store.Store;
import com.abstractfoundry.daemon.bus.Namespace;
import com.abstractfoundry.daemon.rest.representation.FieldRepresentation;
import com.abstractfoundry.daemon.rest.representation.FieldTimeseriesRepresentation;
import com.abstractfoundry.daemon.rest.representation.FieldsRepresentation;
import com.abstractfoundry.daemon.rest.representation.MetadataRepresentation;
import com.abstractfoundry.daemon.rest.representation.ModulesRepresentation;
import com.abstractfoundry.daemon.server.GenericSocketClientHandlerSupplier;
import com.abstractfoundry.daemon.uavcan.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/modules")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ModulesResource {

	private static final Logger logger = LoggerFactory.getLogger(ModulesResource.class);

	// TODO: On error return JSON { "error": ... }.

	@GET
	public Response getModules(@Context Store store) {
		var result = new ModulesRepresentation();
		var names = store.getNamespace().getModuleNames();
		result.setNames(new ArrayList<>(names));
		return Response.ok(result).build();
	}

	@GET // Note: It would be nice here to just delegate to the get_fields method (as POST does to set_fields below), but until it supports including metadata it's not practical.
	@Path("/{moduleName}/fields")
	public Response getFields(@Context Store store, @PathParam("moduleName") String moduleName, @QueryParam("metadata") boolean includeMetadata) {
		var result = new FieldsRepresentation();
		var module = store.getNamespace().getModule(moduleName);
		if (module == null) {
			return Response.status(Status.NOT_FOUND).build();
		}
		var values = new HashMap<String, Object>();
		store.dumpLatestFields(module, values);
		result.setValues(values);
		if (includeMetadata) {
			var fields = module.getSortedFields();
			var metadata = representMetadata(module, fields);
			hackMetadataToExcludeHiddenCubeFields(moduleName, metadata); // TODO: Unhack this.
			result.setMetadata(metadata);
		}
		return Response.ok(result).build();
	}

	@POST // Note: PATCH would be a more appropriate verb, but it is less commonly supported.
	@Path("/{moduleName}/fields")
	public Response postFields(@Context Node node, @Context Store store, @Context GenericSocketClientHandlerSupplier handlerSupplier, @PathParam("moduleName") String moduleName, FieldsRepresentation fields) throws JsonProcessingException {
		var serialiser = new ObjectMapper(); // TODO: Avoid all this serialisation and deserialisation.
		var operand = new HashMap<String, Object>();
		operand.put("arguments", List.of(fields.getValues()));
		var json = serialiser.writeValueAsString(operand);
		return invokeMethod(node, store, handlerSupplier, moduleName, "set_fields", json);
	}

	@GET
	@Path("/{moduleName}/fields/{fieldName}")
	public Response getField(@Context Store store, @PathParam("moduleName") String moduleName, @PathParam("fieldName") String fieldName) {
		var result = new FieldRepresentation();
		var module = store.getNamespace().getModule(moduleName);
		if (module == null) {
			return Response.status(Status.NOT_FOUND).build();
		}
		int id = module.getId();
		int key;
		try {
			key = module.getKey(fieldName);
		} catch (NoSuchElementException exception) {
			return Response.status(Status.NOT_FOUND).build();
		}
		var value = store.getLatestField(id, key);
		result.setValue(value);
		return Response.ok(result).build();
	}

	@POST // Note: PUT would be a more appropriate verb, but it is less commonly supported.
	@Path("/{moduleName}/fields/{fieldName}")
	public Response postField(@Context Node node, @Context Store store, @Context GenericSocketClientHandlerSupplier handlerSupplier, @PathParam("moduleName") String moduleName, @PathParam("fieldName") String fieldName, FieldRepresentation field) throws JsonProcessingException {
		var serialiser = new ObjectMapper(); // TODO: Avoid all this serialisation and deserialisation.
		var operand = new HashMap<String, Object>();
		var argument = new HashMap<String, Object>();
		var value = field.getValue();
		if (value == null || !(value instanceof Number)) {
			return Response.status(Status.BAD_REQUEST).build();
		}
		argument.put(fieldName, value);
		operand.put("arguments", List.of(argument));
		var json = serialiser.writeValueAsString(operand);
		return invokeMethod(node, store, handlerSupplier, moduleName, "set_fields", json);
	}

	@GET
	@Path("/{moduleName}/fields/{fieldName}/timeseries")
	public Response getFieldTimeseries(@Context Store store, @PathParam("moduleName") String moduleName, @PathParam("fieldName") String fieldName) {
		var result = new FieldTimeseriesRepresentation();
		var module = store.getNamespace().getModule(moduleName);
		if (module == null) {
			return Response.status(Status.NOT_FOUND).build();
		}
		int key;
		try {
			key = module.getKey(fieldName);
		} catch (NoSuchElementException exception) {
			return Response.status(Status.NOT_FOUND).build();
		}
		var from = System.currentTimeMillis() - 10L * 1000L; // Note: Go back just 10 seconds by default.
		var timestamps = new long[1024];
		var values = new double[1024];
		var length = store.dumpFieldTimeseries(module, key, from, timestamps, values);
		var entries = new ArrayList<FieldTimeseriesRepresentation.Entry>();
		for (var index = 0; index < length; index++) {
			var entry = new FieldTimeseriesRepresentation.Entry();
			entry.setTimestamp(timestamps[index]);
			entry.setValue(values[index]);
			entries.add(entry);
		}
		result.setEntries(entries);
		return Response.ok(result).build();
	}

	@POST
	@Path("/{moduleName}/methods/{methodName}")
	public Response invokeMethod(@Context Node node, @Context Store store, @Context GenericSocketClientHandlerSupplier handlerSupplier, @PathParam("moduleName") String moduleName, @PathParam("methodName") String methodName, String json) {
		var handler = handlerSupplier.get();
		try {
			var moduleNameBytes = moduleName.getBytes(StandardCharsets.US_ASCII); // TODO: Abstract the interface to Daemon methods, so we don't have to serialize the REST request into a binary command.
			var methodNameBytes = methodName.getBytes(StandardCharsets.US_ASCII);
			var jsonBytes = json.getBytes(StandardCharsets.US_ASCII);
			var future = new CompletableFuture<ByteBuffer>();
			var buffer = ByteBuffer.allocate(32*256*256); // i.e. 2048 KB.
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putInt(0); // Placeholder for total command length.
			buffer.putShort((short) 0); // Correlation number (not used by REST API);
			buffer.putShort((short) 0x10); // Command = Method.
			buffer.put((byte) 0x01); // Type = Module.
			buffer.put((byte) moduleNameBytes.length);
			buffer.put(moduleNameBytes);
			buffer.put((byte) methodNameBytes.length);
			buffer.put(methodNameBytes);
			buffer.put(jsonBytes);
			buffer.putInt(0, buffer.position()); // Finally fill in the command length.
			buffer.flip();
			handler.handle(buffer, future::complete);
			var response = future.get();
			var deserialiser = new ObjectMapper();
			var length = response.getInt(0);
			var result = deserialiser.readTree(response.array(), 6, length - 6);
			var error = result.has("error");
			return Response
				.status(error ? Status.INTERNAL_SERVER_ERROR : Status.OK)
				.entity(result)
				.build();
		} catch (InterruptedException exception) {
			logger.error("Interrupted processing request.", exception);
			Thread.currentThread().interrupt();
			return Response.status(Status.SERVICE_UNAVAILABLE).build();
		} catch (RuntimeException | IOException | ExecutionException exception) {
			logger.error("Error processing request.", exception);
			var report = new HashMap();
			report.put("status", -1);
			report.put("error", exception.getMessage());
			return Response.serverError().entity(report).build();
		}
	}

	private static MetadataRepresentation representMetadata(Namespace.Module module, List<String> fields) {
			var result = new MetadataRepresentation();
			var metadata = module.getMetadata();
			result.setOrder(fields);
			var spanning = new ArrayList<String>();
			var nonpublic = new ArrayList<String>();
			for (var field : fields) {
				var key = module.getKey(field);
				if (metadata.span(key) > 1) {
					spanning.add(field);
				}
				if (metadata.debug(key)) {
					nonpublic.add(field);
				} else if (metadata.system(key)) {
					nonpublic.add(field);
				}
			}
			result.setSpanning(spanning);
			result.setNonpublic(nonpublic);
			return result;
	}

	private void hackMetadataToExcludeHiddenCubeFields(String moduleName, MetadataRepresentation metadata) { // TODO: Unhack this.
		var nonpublic = metadata.getNonpublic();
		if (moduleName.equals("system_button")) {
			nonpublic.add("button_pressed");
			nonpublic.add("button_pressed_count");
		} else if (moduleName.equals("display")) {
			nonpublic.add("panel_width");
			nonpublic.add("panel_height");
			nonpublic.add("gamma_correction_enabled");
			nonpublic.add("gamma_correction_red");
			nonpublic.add("gamma_correction_green");
			nonpublic.add("gamma_correction_blue");
			nonpublic.add("show");
		} else if (moduleName.equals("screen")) {
			nonpublic.add("rotation");
			nonpublic.add("rectangle_x");
			nonpublic.add("rectangle_y");
			nonpublic.add("rectangle_width");
			nonpublic.add("rectangle_height");
			nonpublic.add("rectangle_colour");
			nonpublic.add("rectangle_draw");
			nonpublic.add("text_x");
			nonpublic.add("text_y");
			nonpublic.add("text_size");
			nonpublic.add("text_colour");
			nonpublic.add("text_background_colour");
			nonpublic.add("text_draw");
			nonpublic.add("pixel_window_x");
			nonpublic.add("pixel_window_y");
			nonpublic.add("pixel_window_width");
			nonpublic.add("pixel_window_height");
			nonpublic.add("start_pixel_streaming");
			nonpublic.add("stop_pixel_streaming");
		}
	}

}
