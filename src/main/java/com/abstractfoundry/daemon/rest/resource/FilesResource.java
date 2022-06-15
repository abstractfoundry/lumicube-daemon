/*
 * Copyright (c) 2022 Abstract Foundry Limited
 */

package com.abstractfoundry.daemon.rest.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/files")
public class FilesResource {

	private static final Logger logger = LoggerFactory.getLogger(FilesResource.class);

	@POST
	@Path("/upload")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.TEXT_PLAIN)
	public Response uploadFile(@FormDataParam("file") InputStream file, @FormDataParam("file") FormDataContentDisposition metadata) throws IOException {
		var name = metadata.getFileName();
		if (name == null || name.isBlank()) {
			return Response.status(Status.BAD_REQUEST).entity("No file provided.").build();
		}
		var destination = Paths.get(System.getProperty("user.home"), "Desktop", name);
		Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
		logger.info("Uploaded file to: {}.", destination.toString());
		return Response.ok("File upload complete.").build();
	}

}