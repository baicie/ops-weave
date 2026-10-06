package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual loopback HTTP with explicit in-memory fixture storage, not a PostgreSQL acceptance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "opsweave.inventory.store=memory", "opsweave.auth.mode=dev",
    "opsweave.auth.dev.token=source-continuation-fixture-only-32-characters", "opsweave.auth.dev.subject=fixture-author",
    "opsweave.auth.dev.permissions=entity.read,source.sync", "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-fixture"
})
class SourceContinuationHttpIT {
    @DynamicPropertySource static void tenant(DynamicPropertyRegistry properties) {
        String tenant="source-continuation-"+UUID.randomUUID();properties.add("opsweave.auth.dev.tenant",()->tenant);
    }
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,Object body,boolean authorized)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/"+path)).timeout(Duration.ofSeconds(15));
        if(authorized)request.header("Authorization","Bearer source-continuation-fixture-only-32-characters");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void newestVersionHasAnExplicitReadWithoutChangingTheCreationReceipt()throws Exception {
        var page=CatalogJson.JSON.readTree(call("sources",null,true).body());var model=page.get("models").get(0);
        UUID id=UUID.randomUUID();
        var command=new LinkedHashMap<String,Object>(Map.of("requestId",id,"name","Fixture HTTP continuation","description","Synthetic source only",
            "source",Map.of("kind","MANUAL_SAMPLE","instanceId","manual"),"connectionDigest",page.get("types").get(1).get("connection").get("digest").asString(),
            "target",Map.of("id",model.get("definition").get("id").asString(),"revision",1,"digest",model.get("digest").asString())));
        var confirmed=call("sources/confirm",command,true);assertEquals(200,confirmed.statusCode(),confirmed.body());
        var receipt=CatalogJson.JSON.readTree(confirmed.body());var first=WorkflowJson.decode(receipt.get("workflow").toString());var d=first.definition();
        var second=new WorkflowDefinition(d.id(),2,d.name(),d.source(),d.target(),d.nodes(),d.edges());
        var saved=call("workflows/drafts",Map.of("definition",WorkflowJson.wire(second),"layout",first.layout(),"expectedEditVersion",0),true);
        assertEquals(200,saved.statusCode(),saved.body());
        String path="sources/"+id+"/continuation";var response=call(path,null,true);
        assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        var continuation=CatalogJson.JSON.readTree(response.body());assertEquals(id.toString(),continuation.get("setupId").asString());assertEquals("1.0",continuation.get("schemaVersion").asString());
        assertEquals(CatalogJson.JSON.readTree(saved.body()),continuation.get("workflow"));
        assertEquals(receipt,CatalogJson.JSON.readTree(call("sources/"+id,null,true).body()));
        assertEquals(401,call(path,null,false).statusCode());assertEquals(400,call(path+"?tenantId=other",null,true).statusCode());assertEquals(404,call("sources/"+UUID.randomUUID()+"/continuation",null,true).statusCode());
        id=UUID.randomUUID();command.put("requestId",id);command.remove("target");assertEquals(200,call("sources/confirm",command,true).statusCode());
        assertTrue(CatalogJson.JSON.readTree(call("sources/"+id+"/continuation",null,true).body()).get("workflow").isNull());
    }
}
