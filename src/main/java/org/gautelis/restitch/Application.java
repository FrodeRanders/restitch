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
import org.gautelis.muprocessmanager.MuProcessManagerFactory;
import org.gautelis.restitch.stubbed.StubbedCompensationService;
import org.gautelis.restitch.stubbed.StubbedInvocationService;
import org.gautelis.vopn.db.Database;
import org.gautelis.vopn.db.DatabaseException;
import org.gautelis.vopn.db.utils.MySQL;
import org.gautelis.vopn.db.utils.PostgreSQL;
import org.gautelis.vopn.lang.*;
import org.wso2.msf4j.MicroservicesRunner;
import org.wso2.msf4j.analytics.metrics.MetricsInterceptor;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;


import static java.lang.Runtime.getRuntime;

public class Application {
    private final static Logger log = LogManager.getLogger(Application.class);

    private static final int CONFIGURATION_FAILURE_STATUS = 0;

    private static final String PROCESS_SPECIFICATION_FILE = "RESTITCH_PROCESS_SPECIFICATION_FILE";
    private static final String MANAGEMENT_POLICY_FILE = "RESTITCH_MANAGEMENT_POLICY_FILE";
    private static final String SQL_STATEMENTS_FILE = "RESTITCH_SQL_STATEMENTS_FILE";

    public interface Configuration {
        @Configurable(property = PROCESS_SPECIFICATION_FILE)
        File processSpecification();

        @Configurable(property = MANAGEMENT_POLICY_FILE)
        File managementPolicy();

        @Configurable(property = SQL_STATEMENTS_FILE)
        File sqlStatements();
    }

    public static void main( String... args ) {

        // Setup configuration
        Collection<ConfigurationTool.ConfigurationResolver> resolvers = new ArrayList<>();
        resolvers.add(new SystemEnvironmentConfigurationResolver());

        Map<String, String> defaults = new HashMap<>();
        Configuration configuration = ConfigurationTool.bind(Configuration.class, defaults, resolvers);

        // Load process specification
        File specFile = configuration.processSpecification();
        if (null == specFile || !specFile.exists() || !specFile.canRead()) {
            String info = "Need process specification file name, configured among environment variables as \"" + PROCESS_SPECIFICATION_FILE+ "\"";
            System.err.println(info);
            System.err.println("The contents of this file could be something like:");
            System.err.println("---8<---------------------------------------------------------------------------");
            System.err.println(ProcessSpecification.getExample());
            System.err.println("--------------------------------------------------------------------------->8---");
            System.exit(CONFIGURATION_FAILURE_STATUS);
        }

        // Load SQL statements
        Properties sqlStatements;
        try {
            File statementsFile = configuration.sqlStatements();
            if (null == statementsFile || !statementsFile.exists() || !statementsFile.canRead()) {
                System.out.println("Using default SQL statements");
                sqlStatements = MuProcessManagerFactory.getDefaultSqlStatements();
            } else {
                sqlStatements = MuProcessManagerFactory.getSqlStatements(statementsFile);
            }
        }
        catch (FileNotFoundException | MuProcessException e) {
            String info = "Failed to load SQL statements: ";
            info += e.getMessage();
            log.warn(info, e);

            System.err.println(info);
            System.exit(CONFIGURATION_FAILURE_STATUS);
            return;
        }

        // Load process management policy
        MuProcessManagementPolicy policy;
        try {
            File policyFile = configuration.managementPolicy();
            if (null == policyFile || !policyFile.exists() || !policyFile.canRead()) {
                System.out.println("Using default management policy");
                policy = MuProcessManagerFactory.getManagementPolicy(Application.class, "management-policy.xml");
            } else {
                policy = MuProcessManagerFactory.getManagementPolicy(policyFile);
            }
        }
        catch (FileNotFoundException | MuProcessException e) {
            String info = "Failed to load process management policy: ";
            info += e.getMessage();
            log.warn(info, e);

            System.err.println(info);
            System.exit(CONFIGURATION_FAILURE_STATUS);
            return;
        }

        if (policy.assumeNativeProcessDataFlow()) {
            String info = "Configuration error: Management policy \"assume-native-process-data-flow\" has to be false!";
            System.err.println(info);
            System.exit(CONFIGURATION_FAILURE_STATUS);
        }

        //
        DataSource dataSource = getDataSource();
        MuProcessManager manager = MuProcessManagerFactory.getManager(dataSource, sqlStatements, policy);
        getRuntime().addShutdownHook(new Thread(manager::stop));
        manager.start();

        try {
            MetricsInterceptor metricsInterceptor = new MetricsInterceptor();
            new MicroservicesRunner()
                    .addGlobalRequestInterceptor(metricsInterceptor)
                    .addGlobalResponseInterceptor(metricsInterceptor)
                    .deploy(new ProcessService(manager, configuration))
                    .deploy(new StatusProcessService(manager))
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
            System.exit(CONFIGURATION_FAILURE_STATUS);

        } catch (Throwable t) {
            String info = "Application failure: ";
            info += t.getMessage();
            System.err.println(info);
            t.printStackTrace(System.err);
            System.exit(CONFIGURATION_FAILURE_STATUS);
        }
    }

    /**
     * Determines what database to use, based on some heuristics; we prefer PostgreSQL ahead of MySQL,
     * falling back on an embedded Derby database.
     * @return
     */
    private static DataSource getDataSource() {
        DataSource dataSource = null;

        try {
            // 1. Check whether PostgreSQL is chosen (first)
            String choice = System.getenv("POSTGRESQL_DATABASE");
            if (null == choice || choice.isEmpty()) {

                // 2. Check whether MySQL is chosen (second)
                choice = System.getenv("MYSQL_DATABASE");
                if (null == choice || choice.isEmpty()) {
                    // No database configuration -- fall back on embedded derby
                    System.out.println("Using default backing database");
                    dataSource = MuProcessManagerFactory.getDefaultDataSource("restitch");
                    MuProcessManagerFactory.prepareInternalDatabase(dataSource);

                } else {
                    // MySQL was chosen
                    System.out.println("Using MySQL as backing database");
                    Properties properties = loadProperties(
                            "mysql", 3306,
                            (host, port, database, user, password, props) -> {
                        String url = String.format(
                                "jdbc:mysql://%s:%d/%s?useSSL=false&amp;serverTimezone=UTC",
                                host, port, database
                        );
                        props.setProperty("url", url);
                    });
                    dataSource = MySQL.getDataSource("restitch", Database.getConfiguration(properties));
                }
            } else {
                // PostgreSQL
                System.out.println("Using PostgreSQL as backing database");
                Properties properties = loadProperties(
                        "postgresql", 5433,
                        (host, port, database, user, password, props) -> {
                    String url = String.format(
                            "jdbc:postgresql://%s:%d/%s",
                            host, port, database
                    );
                    props.setProperty("url", url);
                });
                dataSource = PostgreSQL.getDataSource("restitch", Database.getConfiguration(properties));
            }
        }
        catch (DatabaseException | MuProcessException | IOException e) {
            String info = "Failed to establish datasource: ";
            info += e.getMessage();
            log.warn(info, e);

            System.err.println(info);
            System.exit(CONFIGURATION_FAILURE_STATUS);
        }
        return dataSource;
    }

    public interface DatabaseDetailsRunnable {
        void run(String host, int port, String database, String user, String password, Properties properties);
    }


    private static Properties loadProperties(String moniker, int defaultPort, DatabaseDetailsRunnable runnable) throws IOException {
        try (InputStream is = Application.class.getResourceAsStream(moniker.toLowerCase() + "-configuration.xml")) {
            Properties properties = new Properties();
            properties.loadFromXML(is);

            // host
            String host = System.getenv(moniker.toUpperCase() + "_SERVICE_HOST");
            if (null == host || host.isEmpty()) {
                host = moniker.toLowerCase();
            }

            // port
            int port;
            String _port = System.getenv(moniker.toUpperCase() + "_SERVICE_PORT");
            if (null == _port || _port.isEmpty()) {
                port = defaultPort;
            } else {
                port = Integer.parseInt(_port);
            }

            // database
            String database = System.getenv(moniker.toUpperCase() + "_DATABASE_NAME");
            if (null == database || database.isEmpty()) {
                database = "restitch";
            }

            // user
            String user = System.getenv(moniker.toUpperCase() + "_USER");
            if (null == user || user.isEmpty()) {
                user = "restitch";
            }
            properties.setProperty("user", user);

            // password
            String password = System.getenv(moniker.toUpperCase() + "_PASSWORD");
            if (null == password || password.isEmpty()) {
                password = "restitch";
            }
            properties.setProperty("password", password);

            //
            runnable.run(host, port, database, user, password, properties);
            return properties;
        }
    }
}
