package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/**
 * The refusal log is readable under the same authority as the writes it reports, reports field names
 * only, and never replays or authorizes anything.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=rejected-write-http-local-test-only-32ch","opsweave.auth.dev.subject=review-user","opsweave.auth.dev.tenant=tenant-rejected-write-http","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres","opsweave.inventory.cmdb-import-source=cmdb-import"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class RejectedWriteAuditHttpIT {
    static final String TENANT="rejected-write-http-"+UUID.randomUUID();
    static final String TOKEN="rejected-write-http-local-test-only-32ch";
    static final List<String> ROW_FIELDS=List.of("attemptId","kind","method","reasonCode","reasonSummary","actor","fieldNames","attemptedAt");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();final String root="/api/v1/integrations/cmdb/rejected-writes";

    HttpResponse<String> call(String path,String token)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    void seed(String code,String method,List<String> fields){
        wiring.rejectedWrites().record(new RejectedWriteAttempt(UUID.randomUUID(),new TenantId(TENANT),"cmdb-import",
            method.equals("correct-binding")?RejectedWriteAttempt.Kind.BINDING_CORRECTION:RejectedWriteAttempt.Kind.FIELD_REVIEW,
            RejectedWriteAttempt.Method.valueOf(method.toUpperCase(Locale.ROOT).replace('-','_')),
            RejectedWriteAttempt.Code.valueOf(code),"review-user",fields,Instant.now()));
    }

    @Test void readsRefusalsWithStableCodesAndFieldNamesOnly()throws Exception{
        seed("BINDING_FIELDS_ACTIVE","correct-binding",List.of("owner","environment"));
        seed("REVIEW_STATE_CHANGED","decide-review",List.of("ip"));
        var response=call(root,TOKEN);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElse(""));
        var body=SourceReviewJson.JSON.readTree(response.body());
        assertEquals(Set.of("schemaVersion","storage","dataMode","tenantId","sourceInstanceId","limit","items"),names(body));
        assertEquals("import",body.get("dataMode").asString());
        assertEquals(TENANT,body.get("tenantId").asString());
        assertEquals("cmdb-import",body.get("sourceInstanceId").asString());
        assertEquals(20,body.get("limit").asInt());
        assertTrue(body.get("items").size()>=2);
        var first=body.get("items").get(0);
        assertEquals(ROW_FIELDS,names(first),"an audit row carries exactly the published fields");
        assertTrue(Set.of("FIELD_REVIEW","BINDING_CORRECTION").contains(first.get("kind").asString()));
        assertTrue(Set.of("stage-review","decide-review","correct-binding","ingest-snapshot").contains(first.get("method").asString()));
        var code=RejectedWriteAttempt.Code.valueOf(first.get("reasonCode").asString());
        assertEquals(code.summary(),first.get("reasonSummary").asString(),"the summary is the fixed one for the recorded code");
        assertEquals("review-user",first.get("actor").asString());
        assertFalse(response.body().contains("fieldValues"),"no value is ever returned");
    }

    @Test void thePageStaysBoundedAndRejectsUnknownParameters()throws Exception{
        seed("BINDING_CHANGED","correct-binding",List.of("ip"));
        assertEquals(200,call(root+"?limit=1",TOKEN).statusCode());
        assertEquals(1,SourceReviewJson.JSON.readTree(call(root+"?limit=1",TOKEN).body()).get("items").size());
        for(String query:List.of("?limit=0","?limit=101","?limit=abc","?tenantId=other","?limit=1&limit=2","?sourceInstanceId=other","?after=x")){
            assertEquals(400,call(root+query,TOKEN).statusCode(),query);
        }
    }

    @Test void anUnauthenticatedReadIsRefusedWithoutLeakingARow()throws Exception{
        seed("REVIEW_STALE","stage-review",List.of("name"));
        var anonymous=call(root,null);
        assertEquals(401,anonymous.statusCode());
        assertFalse(anonymous.body().contains("REVIEW_STALE"),"a refused read leaks no row");
    }

    static List<String> names(tools.jackson.databind.JsonNode node){
        List<String> result=new ArrayList<>();
        node.propertyNames().forEachRemaining(result::add);
        Collections.sort(result);
        return result;
    }

    /** The same endpoint without the supplemental-source permissions. */
    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=rejected-write-readonly-test-only-32ch","opsweave.auth.dev.subject=read-only-user","opsweave.auth.dev.tenant=tenant-rejected-write-readonly","opsweave.auth.dev.permissions=source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres","opsweave.inventory.cmdb-import-source=cmdb-import"})
    @EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
    static class ReadOnlyAuthorityIT {
        static final String TENANT="rejected-write-readonly-"+UUID.randomUUID();
        static final String TOKEN="rejected-write-readonly-test-only-32ch";
        @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
        @LocalServerPort int port;@Autowired InventoryWiring wiring;

        @Test void sourceSyncAloneCannotReadTheRefusalLog()throws Exception{
            wiring.rejectedWrites().record(new RejectedWriteAttempt(UUID.randomUUID(),new TenantId(TENANT),"cmdb-import",
                RejectedWriteAttempt.Kind.FIELD_REVIEW,RejectedWriteAttempt.Method.STAGE_REVIEW,
                RejectedWriteAttempt.Code.ENTITY_VERSION_CHANGED,"read-only-user",List.of("name"),Instant.now()));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/cmdb/rejected-writes"))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode(),response.body());
            assertFalse(response.body().contains("ENTITY_VERSION_CHANGED"),"a denied read leaks no row");
        }
    }
}
