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

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.gautelis.muprocessmanager.MuForwardActivityContext;
import org.gautelis.muprocessmanager.MuBackwardActivityContext;
import org.gautelis.muprocessmanager.MuForwardBehaviour;
import org.gautelis.muprocessmanager.payload.MuForeignActivityParameters;
import org.gautelis.muprocessmanager.payload.MuForeignProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class UncompensatedActivity implements MuForwardBehaviour {
    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    protected UncompensatedActivity() {
    }

    protected String correlationId = null;
    private URI invocationURI = null;

    public UncompensatedActivity(String correlationId, URI invocationURI) {
        this.correlationId = correlationId;
        this.invocationURI = invocationURI;
    }

    public boolean forward(MuForwardActivityContext context) {
        try {
            /*---------------------------------------------------------------------------------
             * This is enforced elsewhere!
             *
             * if (c.usesNativeDataFlow()) {
             *    throw new Exception("Configuration error: management policy \"assume-native-process-data-flow\" must be false!");
             * }
             *--------------------------------------------------------------------------------*/
            MuForeignActivityParameters activityParameters = (MuForeignActivityParameters) context.getActivityParameters();
            MuForeignProcessResult result = (MuForeignProcessResult) context.getResult();

            return post(correlationId, invocationURI, activityParameters.toJson(), result);

        } catch (Throwable t) {
            String info = "Failed to invoke remote service: ";
            info += t.getMessage();
            log.info(info);
            return false;
        }
    }

    protected static boolean post(String correlationId, URI uri, String json, MuForeignProcessResult result) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            StringEntity requestEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
            HttpPost postMethod = new HttpPost(uri);
            postMethod.setHeader("Correlation-ID", correlationId);
            postMethod.setEntity(requestEntity);

            HttpResponse rawResponse = client.execute(postMethod);
            int status = rawResponse.getStatusLine().getStatusCode();
            String reason = rawResponse.getStatusLine().getReasonPhrase();

            log.trace("Status: {}: {}", status, reason);

            switch (status) {
                case 401: // Unauthorized
                case 403: // Forbidden
                case 404: // Not Found
                case 405: // Method Not Allowed
                case 406: // Not Acceptable
                case 407: // Proxy Authentication Required
                case 410: // Gone
                case 411: // Length Required
                case 412: // Precondition Failed
                case 414: // URI Too Long
                case 415: // Unsupported Media Type
                case 421: // Misdirected Request
                case 426: // Upgrade required
                    log.warn("Problem when communicating with endpoint {}: {} {}", uri, status, reason);
                    break;

                case 408: // Request Timeout
                case 413: // Payload Too Large
                    log.debug("Problem when communicating with endpoint {}: {} {}", uri, status, reason);
                    break;

                case 200:
                    if (null != result) {
                        HttpEntity replyEntity = rawResponse.getEntity();
                        Header replyHeader = replyEntity.getContentType();
                        if (MediaType.APPLICATION_JSON.equals(replyHeader.getValue())) {
                            try (InputStream is = replyEntity.getContent()) {
                                result.add(IOUtils.toString(is));
                            }
                        }
                    }
                    break;
            }

            return 200 == status;
        }
    }
}