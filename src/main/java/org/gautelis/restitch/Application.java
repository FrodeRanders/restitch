package org.gautelis.restitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.gautelis.muprocessmanager.MuProcessException;
import org.gautelis.muprocessmanager.MuProcessManagementPolicy;
import org.gautelis.muprocessmanager.MuProcessManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gautelis.muprocessmanager.payload.MuNativeActivityParameters;
import org.wso2.msf4j.MicroservicesRunner;
import org.wso2.msf4j.analytics.metrics.MetricsInterceptor;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;


import static java.lang.Runtime.getRuntime;

public class Application {
    private final static Logger log = LogManager.getLogger(Application.class);

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public static void main( String... args ) {

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

        MuNativeActivityParameters example = new MuNativeActivityParameters();
        example.put("pizzaId", 101);
        example.put("pizzaName", "Chichen (P)itza");
        example.put("ingredients", new ArrayList<>(Arrays.asList("flour", "eggs", "milk", "salt", "small innocent chickins")));

        System.out.println("-------------------------------------------------");
        System.out.println(gson.toJson(example));
        System.out.println("-------------------------------------------------");

        MuProcessManager manager = MuProcessManager.getManager(dataSource, sqlStatements, policy);
        getRuntime().addShutdownHook(new Thread(() -> manager.stop()));
        manager.start();

        new MicroservicesRunner()
                .addInterceptor(new MetricsInterceptor())
                .deploy(new InvocationService(manager))
                .start();
    }

    private static boolean post(String url) throws IOException {
        String id = "42";
        File file = File.createTempFile("temp-file", "txt");

        try (CloseableHttpClient client = HttpClients.createDefault()) {

            HttpPost postMethod = new HttpPost(url);
            HttpEntity httpEntity = MultipartEntityBuilder.create()
                    .addPart("id", new StringBody(id, ContentType.APPLICATION_FORM_URLENCODED))
                    .addBinaryBody("file", file, ContentType.APPLICATION_OCTET_STREAM, file.getName())
                    .build();
            postMethod.setEntity(httpEntity);

            HttpResponse rawResponse = client.execute(postMethod);
            int status = rawResponse.getStatusLine().getStatusCode();
            String reason = rawResponse.getStatusLine().getReasonPhrase();

            log.trace("Status: {}: {}", status, reason);

            return 200 == status;
        }
    }


    private static void get(String url) throws IOException {

        String id = "42";

        try (CloseableHttpClient client = HttpClients.createMinimal()) {

            String parameterizedUrl = url;
            parameterizedUrl += "?id=" + id;

            HttpGet getMethod = new HttpGet(parameterizedUrl);
            HttpResponse rawResponse = client.execute(getMethod);
            int status = rawResponse.getStatusLine().getStatusCode();
            String reason = rawResponse.getStatusLine().getReasonPhrase();

            log.trace("Status: {}: {}", status, reason);
        }
    }
}
