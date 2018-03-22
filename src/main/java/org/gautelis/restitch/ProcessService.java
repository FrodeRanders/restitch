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
import org.apache.commons.io.IOUtils;
import org.gautelis.muprocessmanager.*;
import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.metrics.core.annotation.Timed;
import org.wso2.msf4j.Request;
import org.wso2.msf4j.analytics.httpmonitoring.HTTPMonitored;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Api(value = "process")
@SwaggerDefinition(
        info = @Info(
                title = "Restitch Process Swagger Definition", version = "1.0",
                description = "Micro process invocation service",
                license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(
                        name = "Frode Randers",
                        email = "Frode.Randers@gmail.com",
                        url = "http://github.com/FrodeRanders/restitch"
                ))
)
@Path("/process")
public class ProcessService {
    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    private final MuProcessManager manager;
    private final ProcessSpecification specification;

    /* package private */ ProcessService(MuProcessManager manager, Application.Configuration configuration) throws IOException {
        this.manager = manager;
        this.specification = ProcessSpecification.getSpecification(configuration);
    }

    /**
     * Invoke process for specified business request (identified by correlation ID), with
     * the provided parameter(s).
     * <p>
     * curl -v -X POST -H "Content-Type:application/json" \
     * -d '{"pizzaId":101,"ingredients":["flour","eggs","milk","salt","small nasty chickins"],"pizzaName":"Chichen (P)itza"}' \
     * http://localhost:8080/process/demo/775113c6-8f7a-4f0d-b5fd-9139727ef224
     * <p>
     */
    @POST
    @Timed
    @Path("/{processMoniker}/{correlationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Invoke process with provided parameters",
            notes = "The provided parameters are distributed to all activities in the process")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Process succeeded"),
            @ApiResponse(code = 412 /* Precondition Failed */, message = "Unknown process or process invocation re-issued"),
            @ApiResponse(code = 500 /* Internal Server Error */, message = "Failed to process request"),
            @ApiResponse(code = 599 /* Process failure */, message = "Failed to process request")})
    public Response invokeProcess(
            @ApiParam(value = "ProcessMoniker", required = true) @PathParam("processMoniker") String processMoniker,
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId,
            @ApiParam(value = "ActionParameters", required = true) @Context Request parameters
    ) {
        String payload;
        try (InputStream is = parameters.getMessageContentStream()) {
            payload = IOUtils.toString(is, StandardCharsets.UTF_8.name());

        } catch (IOException ioe) {
            String info = "Could not read parameters: " + ioe.getMessage();
            log.info(info);
            return Response.status(500).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }

        Optional<List<ProcessSpecification.Specification>> _specificationList = specification.getSpecification(processMoniker);
        if (!_specificationList.isPresent()) {
            String info = "Unknown process: " + processMoniker;
            log.info(info);
            return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }

        List<ProcessSpecification.Specification> specificationList = _specificationList.get();
        if (specificationList.isEmpty()) {
            String info = "Process \"" + processMoniker + "\" has no activities?";
            log.info(info);
            return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }

        MuProcess process = null;
        try {
            // If process is already handled, flag this as an error
            Optional<MuProcessState> status = manager.getProcessState(correlationId);
            if (status.isPresent()) {
                String info = String.format("Business request (referred to by correlation ID \"%s\") was re-issued", correlationId);
                return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }


            try {
                process = manager.newProcess(correlationId);

                MuForeignActivityParameters activityParameters = new MuForeignActivityParameters(payload);

                for (ProcessSpecification.Specification specification : specificationList) {
                    Optional<URI> compensationURI = specification.getCompensationURI();

                    if (compensationURI.isPresent()) {
                        MuOrchestrationParameters orchestrationParameters = new MuOrchestrationParameters();
                        orchestrationParameters.put("compensation-uri", compensationURI.get().toString());
                        process.execute(new CompensatedActivity(correlationId, specification.getInvocationURI()), activityParameters, orchestrationParameters);
                    } else {
                        process.execute(new UncompensatedActivity(correlationId, specification.getInvocationURI()), activityParameters);
                    }
                }
                process.finished();

                return Response.ok(process.getResult().toJson(), MediaType.APPLICATION_JSON_TYPE).build();

            } catch (MuProcessForwardBehaviourException mpfae) {
                // Forward activity failed, but compensations were successful
                String info = String.format("No success, but managed to compensate: %s", mpfae.getMessage());
                log.trace(info);
                return Response.status(599).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();

            } catch (MuProcessBackwardBehaviourException mpbae) {
                // Forward activity failed and so did some compensation activities
                String info = String.format("Process and compensation failure: %s", mpbae.getMessage());
                log.trace(info);
                return Response.status(599).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }
        } catch (Throwable t) {
            // Other reasons for failure not necessarily related to the activity
            if (null != process) {
                process.failed();
            }

            String info = String.format("Process failure: %s", t.getMessage());
            log.warn(info, t);

            return Response.status(500).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }

    /**
     * Retrieve process result(s) for a given business request, identified by correlation ID.
     * <p>
     * curl http://localhost:8080/process/775113c6-8f7a-4f0d-b5fd-9139727ef224
     * <p>
     *
     * @param correlationId ID identifying a unique process handling a specific business request
     * @return Response
     */
    @GET
    @Timed
    @Path("/{correlationId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @ApiOperation(
            value = "Return process result(s), identified by correlation ID of business request",
            notes = "Returns HTTP 404 if the process is not found")
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Valid process"),
            @ApiResponse(code = 204 /* No Content */, message = "No result for this process"),
            @ApiResponse(code = 404 /* Not Found */, message = "Process not found")})
    public Response getProcessResult(
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId
    ) {
        try {
            Optional<MuProcessResult> result = manager.getProcessResult(correlationId);
            if (result.isPresent()) {
                return Response.ok(result.get().toJson(), MediaType.APPLICATION_JSON_TYPE).build();
            } else {
                return Response.ok().status(204).build();
            }
        } catch (MuProcessException mpe) {
            String info = String.format("Failed to retrieve result for process (referred to by correlation ID \"%s\"): ", correlationId);
            info += mpe.getMessage();
            log.info(info, mpe);

            return Response.status(404).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }

    /**
     * Remove specified process (identified by correlation ID)
     * <p>
     * curl -v -X DELETE http://localhost:8080/process/775113c6-8f7a-4f0d-b5fd-9139727ef224
     * <p>
     *
     * @param correlationId ID identifying a unique process handling a specific business request
     * @return Response
     */
    @DELETE
    @Timed
    @Path("/{correlationId}")
    @ApiOperation(
            value = "Reset process, effectively removing all traces of it having occurred",
            notes = "The provided parameter is used to identify the abandoned process")
    @Produces({MediaType.TEXT_PLAIN})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Process reset"),
            @ApiResponse(code = 412 /* Precondition Failed */, message = "Unknown process or process not in a resetable state"),
            @ApiResponse(code = 500 /* Internal Server Error */, message = "Failed to process request"),
            @ApiResponse(code = 598 /* Request failure */, message = "Failed to process request")})
    public Response resetProcess(
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId
    ) {
        try {
            // If process is not in a resetable state, flag this as an error
            Optional<MuProcessState> state = manager.getProcessState(correlationId);
            if (!state.isPresent()) {
                String info = String.format("Process (referred to by correlation ID \"%s\") is unknown", correlationId);
                return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }
            else {
                switch (state.get()) {
                    case COMPENSATION_FAILED:
                        // The process manager may be working on re-compensating this one, but it is valid
                        // to reset and retry
                    case COMPENSATED:
                        // Definitely ok to reset and retry
                        break;

                    case SUCCESSFUL:
                        // Since this could create duplicates - don't allow this
                    case NEW:
                        // This could interfere with a running process - don't allow this
                    case PROGRESSING:
                        // This could interfere with a running process - don't allow this
                    default:
                        String info = String.format("Process (referred to by correlation ID \"%s\") exists but is not currently resetable", correlationId);
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

