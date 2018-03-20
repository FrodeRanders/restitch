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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class ProcessSpecification {
    private static final Gson gson = new GsonBuilder().create();

    public static class Specification {
        URI invocationURI;
        URI compensationURI = null;

        public URI getInvocationURI() {
            return invocationURI;
        }

        public Optional<URI> getCompensationURI() {
            return Optional.ofNullable(compensationURI);
        }
    }

    private HashMap</* process moniker */ String, List<Specification>> processes = new HashMap<>();


    /* package private */ ProcessSpecification() {}

    /* package private */ static ProcessSpecification getSpecification(Application.Configuration configuration) throws IOException {
        File processSpecificationFile = configuration.processSpecification();
        if (null == processSpecificationFile) {
            String info = "Configuration error";
            throw new IllegalArgumentException(info);
        }

        if (!processSpecificationFile.exists()) {
            String info = "No such process specification file: ";
            info += processSpecificationFile.getAbsolutePath();
            throw new IllegalArgumentException(info);
        }

        if (!processSpecificationFile.canRead()) {
            String info = "Cannot read process specification file: ";
            info += processSpecificationFile.getAbsolutePath();
            throw new IllegalArgumentException(info);
        }

        try (Reader reader = new FileReader(processSpecificationFile)) {
            ProcessSpecification specification = gson.fromJson(reader, ProcessSpecification.class);
            specification.processes.forEach((p, sl) -> {
                // Validate process 'p'
                sl.forEach(s -> {
                    if (null == s.getInvocationURI()) {
                        String info = "You must provide at least an invocation URI for all process steps: ";
                        info += "Check configuration of process " + p;
                        throw new IllegalArgumentException(info);
                    }
                });
            });
            return specification;
        }
    }

    public Optional<List<Specification>> getSpecification(String processMoniker) {
        return Optional.ofNullable(processes.get(processMoniker));
    }

    /**
     * Used to get skeleton JSON for process specification (from the horse's mouth, so to say)
     * during development. Not really meant for production use (?) -- anyhow the explicit logging
     * to STDOUT should not remotely be possible since this is a development-time setup.
     * @return example specification JSON
     */
    public static String getExample() {
        ProcessSpecification example = new ProcessSpecification();
        List<Specification> specifications = new LinkedList<>();
        try {
            Specification stepOne = new Specification();
            stepOne.invocationURI = new URI("http://localhost:8080/invoke-stub");
            stepOne.compensationURI = new URI("http://localhost:8080/compensate-stub");
            specifications.add(stepOne);

            Specification stepTwo = new Specification();
            stepTwo.invocationURI = new URI("http://localhost:8080/invoke-stub");
            stepTwo.compensationURI = new URI("http://localhost:8080/compensate-stub");
            specifications.add(stepTwo);

            Specification stepThree = new Specification();
            stepThree.invocationURI = new URI("http://localhost:8080/invoke-stub");
            stepThree.compensationURI = null;
            specifications.add(stepThree);

            example.processes.put("ProcessOne", specifications);
            example.processes.put("ProcessTwo", specifications);

        } catch (URISyntaxException urise) {
            System.out.println("Illegal URI syntax: " + urise);
        }
        return gson.toJson(example);
    }
}
