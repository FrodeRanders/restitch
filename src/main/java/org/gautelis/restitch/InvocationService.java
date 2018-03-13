package org.gautelis.restitch;


import io.swagger.annotations.*;
import org.apache.commons.io.IOUtils;
import org.gautelis.muprocessmanager.*;
import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.gautelis.muprocessmanager.payload.MuNativeProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.metrics.core.annotation.Timed;
import org.wso2.msf4j.Request;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Api(value = "invoke")
@SwaggerDefinition(
        info = @Info(
                title = "Restitch Swagger Definition", version = "1.0",
                description = "Micro process invocation service",
                license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(
                        name = "Frode Randers",
                        email = "Frode.Randers@gmail.com",
                        url = "http://github.com/FrodeRanders/restitch"
                ))
)
@Path("/invoke")
public class InvocationService {
    private static final Logger log = LoggerFactory.getLogger(InvocationService.class);

    private final MuProcessManager manager;

    /* package private */ InvocationService(MuProcessManager manager) {
        this.manager = manager;
    }

    /**
     * Retrieve process result(s) for a given business request, identified by correlation ID.
     * <p>
     * curl http://localhost:8080/invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224
     * <p>
     *
     * @param correlationId ID identifying a unique process handling a specific business request
     * @return Response
     */
    @GET
    @Timed
    @Path("/{correlationId}")
    @Produces({"application/json", "text/xml"})
    @ApiOperation(
            value = "Return result corresponding to the correlation ID",
            notes = "Returns HTTP 404 if the process is not found")
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Valid process"),
            @ApiResponse(code = 204 /* No Content */, message = "No result for this process"),
            @ApiResponse(code = 404 /* Not Found */, message = "Process not found")})
    public Response getResult(@ApiParam(value = "CorrelationId", required = true)
                              @PathParam("correlationId") String correlationId) {

        try {
            Optional<MuProcessResult> result = manager.getProcessResult(correlationId);
            if (result.isPresent()) {
                return Response.ok(result.get().asJson(), MediaType.APPLICATION_JSON_TYPE).build();
            } else {
                return Response.ok().status(204).build();
            }
        } catch (MuProcessException mpe) {
            String info = String.format("Failed to retrieve result for process matching correlation ID \"%s\": ", correlationId);
            info += mpe.getMessage();
            return Response.status(404).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }


    /**
     * Invoke process for specified business request (identified by correlation ID), with
     * the provided parameter(s).
     * <p>
     * curl -v -X POST -H "Content-Type:application/json" \
     * -d '{"pizzaId":101,"ingredients":["flour","eggs","milk","salt","kittens"],"pizzaName":"Chichen (P)itza"}' \
     * http://localhost:8080/invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224
     * <p>
     */
    @POST
    @Timed
    @Path("/{correlationId}")
    @Consumes("application/json")
    @ApiOperation(
            value = "Invoke process with provided parameters",
            notes = "The provided parameters are distributed to all activities in the process")
    @Produces({"application/json", "text/xml"})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "Process succeeded"),
            @ApiResponse(code = 412 /* Precondition Failed */, message = "Invocation re-issued"),
            @ApiResponse(code = 500 /* Internal Server Error */, message = "Failed to process request"),
            @ApiResponse(code = 599 /* Process failure */, message = "Failed to process request")})
    public Response invoke(
            @ApiParam(value = "CorrelationId", required = true) @PathParam("correlationId") String correlationId,
            @ApiParam(value = "Parameters", required = true) @Context Request parameters
    ) {
        String payload;
        try (InputStream is = parameters.getMessageContentStream()) {
            payload = IOUtils.toString(is, StandardCharsets.UTF_8.name());

        } catch (IOException ioe) {
            String info = "Could not read parameters: " + ioe.getMessage();
            log.info(info);
            return Response.status(500).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }

        System.out.println("--8<----------");
        System.out.println(payload);
        System.out.println("---------->8--");
        MuNativeActivityParameters _parameters = new MuNativeActivityParameters();
        _parameters.put("data", payload);

        MuProcess process = null;
        try {
            // If process is already handled, flag this as an error
            Optional<MuProcessState> status = manager.getProcessState(correlationId);
            if (status.isPresent()) {
                String info = String.format("Business request was re-issued for correlation ID \"%s\"", correlationId);
                return Response.status(412).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
            }

            try {
                process = manager.newProcess(correlationId);
                process.execute(
                        (p, r) -> {
                            System.out.println(p);
                            return true;
                        },
                        _parameters
                );
                process.execute(
                        (p, r) -> {
                            return true;
                        },
                        _parameters
                );
                process.execute(
                        (p, r) -> {
                            return true;
                        },
                        _parameters
                );
                MuProcessResult result = process.getResult();
                if (result.isNative()) {
                    ((MuNativeProcessResult)result).add("process ran successfully");
                }
                process.finished();

                return Response.ok(process.getResult().asJson(), MediaType.APPLICATION_JSON_TYPE).build();

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
}

