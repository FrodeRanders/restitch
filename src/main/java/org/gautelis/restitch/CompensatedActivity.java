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

import org.gautelis.muprocessmanager.MuActivity;
import org.gautelis.muprocessmanager.MuActivityParameters;
import org.gautelis.muprocessmanager.MuBackwardActivityContext;
import org.gautelis.muprocessmanager.MuOrchestrationParameters;
import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public class CompensatedActivity extends UncompensatedActivity implements MuActivity {
    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    /*
     * Used by the compensation facilities, where correlation Id et al is not needed
     */
    public CompensatedActivity() {}

    public CompensatedActivity(String correlationId, URI invocationURI) {
        super(correlationId, invocationURI);
    }

    public boolean backward(MuBackwardActivityContext context) {
        MuActivityParameters activityParameters = context.getActivityParameters();
        Optional<MuOrchestrationParameters> orchestrationParameters = context.getOrchestrationParameters();

        if (!orchestrationParameters.isPresent()) {
            String info = "Compensation activity needs orchestration data";
            log.warn(info);
            return false;
        }

        String compensationURI = orchestrationParameters.get().get("compensation-uri");
        if (null == compensationURI || compensationURI.isEmpty()) {
            String info = "Compensation activity needs orchestration data, in this case URI to (remote) service";
            log.warn(info);
            return false;
        }

        try {
            /*---------------------------------------------------------------------------------
             * This is enforced elsewhere!
             *
             * if (c.usesNativeDataFlow()) {
             *    throw new Exception("Configuration error: management policy \"assume-native-process-data-flow\" must be false!");
             * }
             *--------------------------------------------------------------------------------*/
            MuForeignActivityParameters params = (MuForeignActivityParameters) context.getActivityParameters();

            return post(correlationId, new URI(compensationURI), params.toJson());

        } catch (URISyntaxException use) {
            String info = "The value provided as compensation instance data (\"";
            info += compensationURI;
            info += "\") does not qualify as a URI: ";
            info += use.getMessage();
            log.info(info);
            return false;

        } catch (Throwable t) {
            String info = "Failed to invoke remote service: ";
            info += t.getMessage();
            log.info(info);
            return false;
        }
    }

    protected static boolean post(String correlationId, URI uri, String json) throws IOException {
        return post(correlationId, uri, json, null);
    }
}
