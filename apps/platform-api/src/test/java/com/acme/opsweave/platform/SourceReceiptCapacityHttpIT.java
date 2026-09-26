package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.SourceReceiptCapacity;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/**
 * The capacity view is readable under the same authority as the writes the cap protects, reports both
 * caps, and never claims a receipt was removed or a cap raised.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=receipt-capacity-http-local-test-32ch","opsweave.auth.dev.subject=capacity-user","opsweave.auth.dev.tenant=tenant-receipt-capacity-http","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres","opsweave.inventory.cmdb-import-source=cmdb-import"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceReceiptCapacityHttpIT {
    static final String TENANT="receipt-capacity-http-"+UUID.randomUUID();
    static final String TOKEN="receipt-capacity-http-local-test-32ch";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();final String root="/api/v1/integrations/cmdb/receipt-capacity";

    HttpResponse<String> call(String path,String token)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void reportsBothCapsForAnEmptySource()throws Exception{
        var response=call(root,TOKEN);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElse(""));
        var body=SourceReviewJson.JSON.readTree(response.body());
        assertEquals(Set.of("schemaVersion","storage","dataMode","tenantId","sourceInstanceId","items"),names(body));
        assertEquals("import",body.get("dataMode").asString());
        assertEquals(TENANT,body.get("tenantId").asString());
        assertEquals("cmdb-import",body.get("sourceInstanceId").asString());
        assertEquals(2,body.get("items").size());
        var snapshot=body.get("items").get(0);
        assertEquals(Set.of("kind","kept","max","status","summary"),names(snapshot));
        assertEquals("snapshot",snapshot.get("kind").asString());
        assertEquals(0,snapshot.get("kept").asInt());
        assertEquals(SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT),snapshot.get("max").asInt());
        assertEquals("OK",snapshot.get("status").asString());
        assertEquals("binding-correction",body.get("items").get(1).get("kind").asString());
        assertFalse(response.body().contains("removed"),"the view never claims a cleanup");
        assertFalse(response.body().contains("raised"),"the view never claims a raised cap");
    }

    @Test void takesNoParametersAndRequiresAuthentication()throws Exception{
        for(String query:List.of("?limit=1","?tenantId=other","?sourceInstanceId=other","?after=x")){
            assertEquals(400,call(root+query,TOKEN).statusCode(),query);
        }
        var anonymous=call(root,null);
        assertEquals(401,anonymous.statusCode());
        assertFalse(anonymous.body().contains("snapshot"),"a refused read leaks no capacity row");
    }

    static List<String> names(tools.jackson.databind.JsonNode node){
        List<String> names=new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        Collections.sort(names);
        return names;
    }
}

/** The same endpoint without the supplemental-source permissions. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=receipt-capacity-readonly-test-32ch","opsweave.auth.dev.subject=read-only-user","opsweave.auth.dev.tenant=tenant-receipt-capacity-readonly","opsweave.auth.dev.permissions=source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres","opsweave.inventory.cmdb-import-source=cmdb-import"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceReceiptCapacityReadOnlyHttpIT {
    static final String TENANT="receipt-capacity-readonly-"+UUID.randomUUID();
    static final String TOKEN="receipt-capacity-readonly-test-32ch";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;

    @Test void sourceSyncAloneCannotReadTheCapacityView()throws Exception{
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/cmdb/receipt-capacity"))
            .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,response.statusCode(),response.body());
        assertFalse(response.body().contains("snapshot"),"a denied read leaks no capacity row");
    }
}
