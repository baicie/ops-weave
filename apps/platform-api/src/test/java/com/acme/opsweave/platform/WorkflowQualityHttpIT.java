package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual HTTP boundary checks. No source samples or output are requested. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=quality-http-fixture-only-32-characters","opsweave.auth.dev.subject=quality-author","opsweave.auth.dev.permissions=entity.read,log.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowQualityHttpIT {
    static final String ROOT="/api/v1/integrations/workflows/quality/workflows/missing-fixture/versions/1";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->"quality-http-fixture-"+UUID.randomUUID());r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,boolean auth,String method)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer quality-http-fixture-only-32-characters");b.method(method,HttpRequest.BodyPublishers.noBody());return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    @Test void missingHistoryIs404AndTrustedSessionIsRequired()throws Exception{var r=call(ROOT,true,"GET");assertEquals(404,r.statusCode());assertEquals("NOT_FOUND",CatalogJson.JSON.readTree(r.body()).get("error").asString());assertEquals(401,call(ROOT,false,"GET").statusCode());assertEquals(404,call(ROOT+"/batches/"+UUID.randomUUID(),true,"GET").statusCode());}
    @Test void identityQueryMalformedScopeAndUnsupportedMutationAreRejected()throws Exception{assertEquals(400,call(ROOT+"?tenantId=forged",true,"GET").statusCode());assertEquals(400,call(ROOT.replace("/versions/1","/versions/0"),true,"GET").statusCode());assertEquals(400,call(ROOT+"/batches/1-1-1-1-1",true,"GET").statusCode());assertEquals(405,call(ROOT,true,"POST").statusCode());}
}
