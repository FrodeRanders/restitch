/*
 * Copyright (C) 2018 Frode Randers
 * All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.gautelis.restitch;

import io.swagger.annotations.*;
import org.gautelis.muprocessmanager.MuProcessDetails;
import org.gautelis.muprocessmanager.MuProcessException;
import org.gautelis.muprocessmanager.MuProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.metrics.core.annotation.Timed;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.Optional;

@Api(value = "status")
@SwaggerDefinition(
        info = @Info(
                title = "Restitch Status Swagger Definition", version = "1.0",
                description = "Process status service",
                license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(
                        name = "Frode Randers",
                        email = "Frode.Randers@gmail.com",
                        url = "http://github.com/FrodeRanders/restitch"
                ))
)
@Path("/status")
public class StatusProcessService {
    private static final Logger log = LoggerFactory.getLogger(StatusProcessService.class);

    private final MuProcessManager manager;

    /* package private */ StatusProcessService(MuProcessManager manager) {
        this.manager = manager;
    }

    /**
     * Retrieve status for all processes
     * <p>
     * curl http://localhost:8080/status
     * @return collection of processes' details
     */
    @GET
    @Timed
    @Path("/")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @ApiOperation(
            value = "Return list of processes' details, identified by correlation ID",
            notes = "Contains details about processes")
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "OK"),
            @ApiResponse(code = 598 /* Request failure */, message = "Failed to process request")})
    public Response getProcessStatus() {
        try {
            Collection<MuProcessDetails> details = manager.getProcessDetails();
            return Response.ok(details, MediaType.APPLICATION_JSON_TYPE).build();

        } catch (MuProcessException mpe) {
            String info = "Failed to retrieve process details: ";
            info += mpe.getMessage();
            log.info(info, mpe);

            return Response.status(598).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }


    /**
     * Remove specified abandoned process (identified by correlation ID).
     * <p>
     * curl http://localhost:8080/status/775113c6-8f7a-4f0d-b5fd-9139727ef224
     */
    @GET
    @Timed
    @Path("/{correlationId}")
    @ApiOperation(
            value = "Return details for process identified by correlation ID",
            notes = "The provided parameter is used to identify the process")
    @Produces({MediaType.TEXT_PLAIN})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Process reset"),
            @ApiResponse(code = 412 /* Precondition Failed */, message = "Unknown process"),
            @ApiResponse(code = 500 /* Internal Server Error */, message = "Failed to process request"),
            @ApiResponse(code = 598 /* Request failure */, message = "Failed to process request")})
    public Response getProcessStatus(
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId
    ) {
        try {
            Optional<MuProcessDetails> details = manager.getProcessDetails(correlationId);
            if (!details.isPresent()) {
                String info = String.format("Process (referred to by correlation ID \"%s\") is unknown", correlationId);
                return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }

            return Response.ok(details.get(), MediaType.APPLICATION_JSON_TYPE).build();

        } catch (MuProcessException mpe) {
            // Reset request failed
            String info = String.format("Failed to retrieve status for process (referred to by correlation ID \"%s\"): %s", correlationId, mpe.getMessage());
            log.info(info, mpe);

            return Response.status(598).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();

        } catch (Throwable t) {
            String info = String.format("Failed to process request: %s", t.getMessage());
            log.warn(info, t);
            return Response.status(500).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }
}

