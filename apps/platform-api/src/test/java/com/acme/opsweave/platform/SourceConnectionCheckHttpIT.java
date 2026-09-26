package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=connection-check-http-local-test-only-32-chars","opsweave.auth.dev.subject=connection-user","opsweave.auth.dev.tenant=tenant-connection-http","opsweave.auth.dev.permissions=source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceConnectionCheckHttpIT {
    static final String TENANT="connection-check-http-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();final String root="/api/v1/integrations/zabbix/connection-checks";
    HttpResponse<String> call(String path,String method,boolean auth)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(auth)builder.header("Authorization","Bearer connection-check-http-local-test-only-32-chars");
        return http.send("POST".equals(method)?builder.POST(HttpRequest.BodyPublishers.noBody()).build():builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void runsAndReadsALabeledSelfCheckWithoutClaimingAVendorVersion()throws Exception{
        var post=call(root,"POST",true);
        assertEquals(200,post.statusCode(),post.body());
        assertEquals("no-store",post.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff",post.headers().firstValue("X-Content-Type-Options").orElse(""));
        var body=SourceReviewJson.JSON.readTree(post.body());
        assertEquals("connection-check",body.get("dataMode").asString());
        assertEquals(TENANT,body.get("tenantId").asString());
        assertEquals("zabbix-1",body.get("sourceInstanceId").asString());
        var check=body.get("check");
        assertTrue(check.get("reachable").asBoolean());
        assertEquals("labeled-fixture",check.get("statusCode").asString());
        assertEquals("labeled-fixture",check.get("dataMode").asString());
        assertEquals("connection-user",check.get("actor").asString());
        assertTrue(check.get("reportedVersion").isNull(),"the fixture probe claims no vendor version");

        var list=call(root+"?limit=5","GET",true);
        assertEquals(200,list.statusCode(),list.body());
        var page=SourceReviewJson.JSON.readTree(list.body());
        assertEquals(5,page.get("limit").asInt());
        assertEquals(check.get("checkId").asString(),page.get("items").get(0).get("checkId").asString());
        assertEquals(1,wiring.sourceChecks().kept(new com.acme.opsweave.sharedkernel.TenantId(TENANT),"zabbix-1"));

        var dir=java.nio.file.Path.of("../../.tmp/source-connection-http");java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("source-connection-check-receipt.json"),post.body());
        java.nio.file.Files.writeString(dir.resolve("source-connection-check-page.json"),list.body());
    }

    @Test void rejectsUnboundedQueriesAndUnauthenticatedChecks()throws Exception{
        assertEquals(401,call(root,"GET",false).statusCode());
        assertEquals(401,call(root,"POST",false).statusCode());
        for(String query:List.of("?limit=0","?limit=51","?limit=abc","?tenantId=other","?limit=1&limit=2"))
            assertEquals(400,call(root+query,"GET",true).statusCode(),query);
        assertEquals(400,call(root+"?limit=1","POST",true).statusCode());
        assertEquals(400,call(root+"?tenantId=other","POST",true).statusCode());
    }
}
