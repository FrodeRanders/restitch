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

import org.gautelis.muprocessmanager.MuProcessException;
import org.gautelis.muprocessmanager.MuProcessManagementPolicy;
import org.gautelis.muprocessmanager.MuProcessManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gautelis.restitch.stubbed.StubbedCompensationService;
import org.gautelis.restitch.stubbed.StubbedInvocationService;
import org.gautelis.vopn.lang.*;
import org.wso2.msf4j.MicroservicesRunner;
import org.wso2.msf4j.analytics.metrics.MetricsInterceptor;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.*;


import static java.lang.Runtime.getRuntime;

public class Application {
    private final static Logger log = LogManager.getLogger(Application.class);

    private static final String PROCESS_SPECIFICATION_FILE = "RESTITCH_PROCESS_SPECIFICATION_FILE";
    public interface Configuration {
        @Configurable(property=PROCESS_SPECIFICATION_FILE)
        File processSpecification();
    }

    public static void main( String... args ) {
        String configFileName = System.getenv(PROCESS_SPECIFICATION_FILE);
        if (null == configFileName || configFileName.length() == 0) {
            String info = "Need configuration file name, configured among environment variables as \"" + PROCESS_SPECIFICATION_FILE+ "\"";
            System.err.println(info);
            System.err.println("The contents of this file could be something like:");
            System.err.println("---8<------------------------------------------------------------------------------------");
            System.err.println(ProcessSpecification.getExample());
            System.err.println("------------------------------------------------------------------------------------>8---");
            System.exit(1);
        }

        Collection<ConfigurationTool.ConfigurationResolver> resolvers = new ArrayList<>();

        // <<<"Check among system environment">>>-resolver
        resolvers.add(new SystemEnvironmentConfigurationResolver());

        /*
        // <<<"Check among bundled resources">>>-resolver
        try {
            resolvers.add(new BundledPropertiesConfigurationResolver(Application.class, "default-process-specification"));
        }
        catch (IOException ioe) {
            String info = "Failed to load bundled default process specification: " + ioe.getMessage();
            System.err.println(info);
            System.exit(1);
        }
        */

        Map<String, Object> noDefaults = new HashMap<>();
        Configuration configuration = ConfigurationTool.bind(Configuration.class, noDefaults, resolvers);

        // Load default database configuration and initiate
        DataSource dataSource;
        try {
            dataSource = MuProcessManager.getDefaultDataSource("restitch");
            MuProcessManager.prepareInternalDatabase(dataSource);
        }
        catch (MuProcessException mpe) {
            String info = "Failed to establish datasource: ";
            info += mpe.getMessage();
            log.warn(info, mpe);

            System.err.println(info);
            System.exit(1);
            return;
        }

        // Load default (internal) SQL statements
        Properties sqlStatements;
        try {
            sqlStatements = MuProcessManager.getDefaultSqlStatements();
        }
        catch (MuProcessException mpe) {
            String info = "Failed to load SQL statements: ";
            info += mpe.getMessage();
            log.warn(info, mpe);

            System.err.println(info);
            System.exit(1);
            return;
        }

        MuProcessManagementPolicy policy;
        try {
            policy = MuProcessManager.getManagmentPolicy(Application.class,"management-policy.xml");
        }
        catch (MuProcessException mpe) {
            String info = "Failed to load process management policy: ";
            info += mpe.getMessage();
            log.warn(info, mpe);

            System.err.println(info);
            System.exit(1);
            return;
        }

        if (policy.assumeNativeProcessDataFlow()) {
            String info = "Configuration error: Management policy \"assume-native-process-data-flow\" has to be false!";
            System.err.println(info);
            System.exit(1);
        }

        MuProcessManager manager = MuProcessManager.getManager(dataSource, sqlStatements, policy);
        getRuntime().addShutdownHook(new Thread(() -> manager.stop()));
        manager.start();

        try {
            new MicroservicesRunner()
                    .addInterceptor(new MetricsInterceptor())
                    .deploy(new ProcessService(manager, configuration))
                    .deploy(new AbandonedProcessService(manager))
                    // Non-important stuff
                    .deploy(new StubbedInvocationService())
                    .deploy(new StubbedCompensationService())
                    .start();

            System.out.println("Services running...");

        } catch (IOException ioe) {
            String info = "Could not load process specifications: ";
            info += ioe.getMessage();
            System.err.println(info);
            System.exit(1);

        } catch (Throwable t) {
            String info = "Application failure: ";
            info += t.getMessage();
            System.err.println(info);
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
