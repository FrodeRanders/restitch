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
package org.gautelis.restitch.stubbed;

import io.swagger.annotations.*;
import org.apache.commons.io.IOUtils;
import org.gautelis.restitch.ProcessService;
import org.gautelis.restitch.ProcessSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.metrics.core.annotation.Timed;
import org.wso2.msf4j.Request;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Api(value = "compensate-stub")
@SwaggerDefinition(
        info = @Info(
                title = "Restitch Stubbed Compensator Swagger Definition", version = "1.0",
                description = "Stubbed process activity service, suitable for testing purposes",
                license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0"),
                contact = @Contact(
                        name = "Frode Randers",
                        email = "Frode.Randers@gmail.com",
                        url = "http://github.com/FrodeRanders/restitch"
                ))
)
@Path("/compensate-stub")
public class StubbedCompensationService {
    private static final Logger log = LoggerFactory.getLogger(StubbedCompensationService.class);

    private static long counter = 0L;
    private static final double backwardFailureProbability = 0.01;

    public StubbedCompensationService() {
    }

    /**
     * Stubbed behaviour for compensating activity, with the provided parameter(s).
     * <p>
     * curl -v -X POST -H "Content-Type:application/json" \
     * -d '{"pizzaId":101,"ingredients":["flour","eggs","milk","salt","small nasty chickins"],"pizzaName":"Chichen (P)itza"}' \
     * http://localhost:8080/fetch-stub
     * <p>
     */
    @POST
    @Timed
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Compensate stub",
            notes = "Compensates failed activity")
    @Produces({MediaType.TEXT_PLAIN})
    @ApiResponses(value = {
            @ApiResponse(code = 200 /* OK */, message = "OK"),
            @ApiResponse(code = 598 /* Process failure */, message = "Failed to process request")})
    public Response compensate(
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

        System.out.println("STUBBED compensation " + (++counter) + " (got " + payload.getBytes().length + " bytes)");

        if (!(Math.random() < backwardFailureProbability)) {
            return Response.ok().build();
        }
        else {
            String info = "Simulated compensation failure";
            return Response.status(598).type(MediaType.TEXT_PLAIN_TYPE).entity(info).build();
        }
    }
}

