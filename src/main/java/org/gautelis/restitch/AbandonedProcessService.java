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
import org.gautelis.muprocessmanager.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.metrics.core.annotation.Timed;
import org.wso2.msf4j.analytics.httpmonitoring.HTTPMonitored;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.Optional;

@Api(value = "abandoned")
@SwaggerDefinition(
        info = @Info(
                title = "Restitch Abandoned Swagger Definition", version = "1.0",
                description = "Micro process invocation service",
                license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(
                        name = "Frode Randers",
                        email = "Frode.Randers@gmail.com",
                        url = "http://github.com/FrodeRanders/restitch"
                ))
)
@Path("/abandoned")
public class AbandonedProcessService {
    private static final Logger log = LoggerFactory.getLogger(AbandonedProcessService.class);

    private final MuProcessManager manager;

    /* package private */ AbandonedProcessService(MuProcessManager manager) {
        this.manager = manager;
    }

    /**
     * Retrieve a list of abandoned processes
     * <p>
     * curl http://localhost:8080/abandoned
     * @return collection of abandoned processes' details
     */
    @GET
    @Timed
    @Path("/")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @ApiOperation(
            value = "Return list of abandoned processes' details, identified by correlation ID",
            notes = "Contains details about failed processes having activities that could not be compensated")
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "OK"),
            @ApiResponse(code = 598 /* Request failure */, message = "Failed to process request")})
    public Response getAbandonedProcesses() {
        try {
            Collection<MuProcessDetails> details = manager.getAbandonedProcessesDetails();
            return Response.ok(details, MediaType.APPLICATION_JSON_TYPE).build();

        } catch (MuProcessException mpe) {
            String info = "Failed to retrieve abandoned process details: ";
            info += mpe.getMessage();
            log.info(info, mpe);

            return Response.status(598).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }


    /**
     * Remove specified abandoned process (identified by correlation ID).
     * <p>
     * curl -v -X DELETE http://localhost:8080/abandoned/775113c6-8f7a-4f0d-b5fd-9139727ef224
     */
    @DELETE
    @Timed
    @Path("/{correlationId}")
    @ApiOperation(
            value = "Reset abandoned process, effectively removing all traces of it having occurred",
            notes = "The provided parameter is used to identify the abandoned process")
    @Produces({MediaType.TEXT_PLAIN})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Process reset"),
            @ApiResponse(code = 412 /* Precondition Failed */, message = "Unknown process or process not abandoned"),
            @ApiResponse(code = 500 /* Internal Server Error */, message = "Failed to process request"),
            @ApiResponse(code = 598 /* Request failure */, message = "Failed to process request")})
    public Response resetAbandonedProcess(
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId
    ) {
        try {
            // If process is not abandoned handled, flag this as an error
            Optional<MuProcessState> state = manager.getProcessState(correlationId);
            if (!state.isPresent()) {
                String info = String.format("Process (referred to by correlation ID \"%s\") is unknown", correlationId);
                return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }
            else {
                if (state.get() != MuProcessState.ABANDONED) {
                    String info = String.format("Process (referred to by correlation ID \"%s\") exists but is not abandoned", correlationId);
                    return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
                }
            }

            Optional<Boolean> success = manager.resetProcess(correlationId);
            if (success.isPresent() && success.get()) {
                return Response.ok("Process reset", MediaType.TEXT_PLAIN_TYPE).build();
            }
            else {
                // Not really interesting, indicating process not found and effectively meaning
                // the process was reset (by someone else) during the last milliseconds or so,
                // effectively doing our bidding.
                // We'll keep this if statement branch for clarity.
                return Response.ok("Process reset", MediaType.TEXT_PLAIN_TYPE).build();
            }
        } catch (MuProcessException mpe) {
            // Reset request failed
            String info = String.format("Failed to reset process (referred to by correlation ID \"%s\"): %s", correlationId, mpe.getMessage());
            log.info(info, mpe);

            return Response.status(598).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();

        } catch (Throwable t) {
            String info = String.format("Failed to process request: %s", t.getMessage());
            log.warn(info, t);
            return Response.status(500).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }
}

