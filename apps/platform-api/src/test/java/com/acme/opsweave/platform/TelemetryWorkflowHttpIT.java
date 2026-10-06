package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Real local HTTP/PG with explicit synthetic samples, and source.sync without entity.read. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=telemetry-http-fixture-only-32-characters","opsweave.auth.dev.subject=telemetry-author","opsweave.auth.dev.permissions=source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class TelemetryWorkflowHttpIT {
    static final String TENANT="telemetry-http-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/"+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer telemetry-http-fixture-only-32-characters");
        if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    WorkflowDefinition definition(String id,String kind){
        var fields=TelemetryOutput.fields(kind);var mapping=new LinkedHashMap<String,String>();for(String f:fields)mapping.put(f,f);
        return com.acme.opsweave.integration.domain.WorkflowOperators.builtIn().pin(new WorkflowDefinition(id,1,"Fixture "+kind,new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,kind),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",mapping),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","mapping"),new Edge("mapping","validate"),new Edge("validate","output"))));
    }
    Map<String,Object> save(WorkflowDefinition d){var layout=new LinkedHashMap<String,Position>();for(var n:d.nodes())layout.put(n.id(),new Position(0,0));return Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",0);}
    Map<String,Object> preview(WorkflowDefinition d,Map<String,Object> sample){return Map.of("id",d.id(),"revision",1,"editVersion",1,"digest",d.digest(),"dryRun",true,"samples",List.of(sample));}

    @Test void sourceOnlyPersistsThenReopensExplicitLogDraftWithoutCatalogPermission()throws Exception{
        var page=call("sources",null);assertEquals(200,page.statusCode(),page.body());var p=CatalogJson.JSON.readTree(page.body());assertEquals(0,p.get("models").size());
        String id=UUID.randomUUID().toString();var command=Map.of("requestId",id,"name","Fixture source only","description","Synthetic HTTP check","source",Map.of("kind","MANUAL_SAMPLE","instanceId","manual"),"connectionDigest",p.get("types").get(1).get("connection").get("digest").asString());
        var confirmed=call("sources/confirm",command);assertEquals(200,confirmed.statusCode(),confirmed.body());var receipt=CatalogJson.JSON.readTree(confirmed.body());assertTrue(receipt.get("setup").get("initialTarget").isNull());assertTrue(receipt.get("workflow").isNull());assertEquals(receipt,CatalogJson.JSON.readTree(call("sources/confirm",command).body()));assertEquals(receipt,CatalogJson.JSON.readTree(call("sources/"+id,null).body()));
        assertEquals(404,call("workflows/drafts/source-"+id+"/1",null).statusCode());
        var d=definition("source-"+id,"LOG");var saved=call("workflows/drafts",save(d));assertEquals(200,saved.statusCode(),saved.body());
        var reopened=CatalogJson.JSON.readTree(call("sources/"+id,null).body());assertTrue(reopened.get("setup").get("initialTarget").isNull());assertEquals("LOG",reopened.get("workflow").get("definition").get("target").get("kind").asString());assertEquals(receipt.get("setup"),reopened.get("setup"));
        assertEquals(0,CatalogJson.JSON.readTree(call("workflows",null).body()).get("models").size());
    }
    @Test void typedPreviewsPublishAndPgTracesRoundTripWithoutPersistingSampleBodies()throws Exception{
        for(String kind:List.of("LOG","METRIC")){
            var d=definition("http-"+UUID.randomUUID(),kind);assertEquals(200,call("workflows/drafts",save(d)).statusCode());
            Map<String,Object> sample=kind.equals("LOG")?Map.of("eventTime","2026-10-01T08:00:00+08:00","body","  Fixture private log payload  ","severityText","INFO"):Map.of("timestamp","2026-10-01T00:00:00Z","metricKey","fixture.cpu","value","0.50","metricType","GAUGE","unit","ratio");
            var result=call("workflows/preview",preview(d,sample));assertEquals(200,result.statusCode(),result.body());var r=CatalogJson.JSON.readTree(result.body());assertEquals(1,r.get("receipt").get("accepted").asInt());assertFalse(r.get("evaluation").get("writesPerformed").asBoolean());
            var values=r.get("evaluation").get("rows").get(0).get("steps").get(3).get("values");assertEquals("2026-10-01T00:00:00Z",values.get(kind.equals("LOG")?"eventTime":"timestamp").asString());if(kind.equals("LOG"))assertEquals(sample.get("body"),values.get("body").asString());
            String runId=r.get("receipt").get("id").asString();var detail=call("workflows/runs/"+runId,null);assertEquals(200,detail.statusCode(),detail.body());var trace=CatalogJson.JSON.readTree(detail.body()).get("trace");assertEquals(2,trace.get("target").size());assertEquals(kind,trace.get("target").get("kind").asString());assertFalse(detail.body().contains("Fixture private log payload"));assertFalse(detail.body().contains("values"));
            var publish=call("workflows/publish",Map.of("id",d.id(),"revision",1,"editVersion",1,"digest",d.digest(),"previewId",runId));assertEquals(200,publish.statusCode(),publish.body());assertEquals(CatalogJson.JSON.readTree(publish.body()),CatalogJson.JSON.readTree(call("workflows/versions/"+d.id()+"/1",null).body()));
            var bad=new LinkedHashMap<>(sample);bad.remove(kind.equals("LOG")?"eventTime":"value");var run=new LinkedHashMap<>(preview(d,bad));run.put("editVersion",0);var rejected=call("workflows/run",run);assertEquals(200,rejected.statusCode(),rejected.body());assertEquals(1,CatalogJson.JSON.readTree(rejected.body()).get("receipt").get("rejected").asInt());
        }
    }
    @Test void wrongSourceTargetAndEntityPermissionRemainServerEnforced()throws Exception{
        var d=definition("http-"+UUID.randomUUID(),"LOG");var wire=new LinkedHashMap<>(WorkflowJson.wire(d));wire.put("source",Map.of("kind","ZABBIX_HOST","instanceId","zabbix-1"));var request=new LinkedHashMap<>(save(d));request.put("definition",wire);assertEquals(400,call("workflows/drafts",request).statusCode());
        wire.put("source",Map.of("kind","MANUAL_SAMPLE","instanceId","manual"));wire.put("target",Map.of("kind","LOG","schemaVersion","1.0","id","builtin.host"));assertEquals(400,call("workflows/drafts",request).statusCode());
        var model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals("builtin.service")).findFirst().orElseThrow();var entity=new WorkflowDefinition(d.id(),1,d.name(),d.source(),new Target(model.id(),1,model.digest()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",Map.of("name","name")),d.nodes().get(2),d.nodes().get(3)),d.edges());assertEquals(403,call("workflows/drafts",save(entity)).statusCode());
    }

    @Test void standardMappingRequiresMetricPermissionAndDirectoryIsFiltered()throws Exception{
        var page=call("workflows",null);assertEquals(200,page.statusCode());assertEquals(0,CatalogJson.JSON.readTree(page.body()).get("metricMappings").size());
        var m=com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader()).definitions().getFirst();
        var legacy=definition("http-"+UUID.randomUUID(),"METRIC");var nodes=new ArrayList<>(legacy.nodes());nodes.set(1,WorkflowOperators.builtIn().pin(new Node("mapping",Type.MAP,"1",Map.of("timestamp","timestamp","value","value","sourceKey","sourceKey"))));
        var d=new WorkflowDefinition(legacy.id(),1,legacy.name(),legacy.source(),new Target(null,1,null,"METRIC",m.pin(),m.metricKey()),nodes,legacy.edges());
        assertEquals(403,call("workflows/drafts",save(d)).statusCode());assertEquals(404,call("workflows/drafts/"+d.id()+"/1",null).statusCode());
    }

    @Test void logPermissionsAreSeparateFromWorkflowEditing()throws Exception{
        var cap=call("workflows/log-outputs/workflows/fixture-log/capability",null);assertEquals(200,cap.statusCode());var flags=CatalogJson.JSON.readTree(cap.body());assertFalse(flags.get("readAllowed").asBoolean());assertFalse(flags.get("writeAllowed").asBoolean());
        assertEquals(403,call("workflows/log-outputs",Map.of("requestId",UUID.randomUUID(),"id","fixture-log","revision",1,"digest","sha256:"+"a".repeat(64),"previewId",UUID.randomUUID(),"samples",List.of(Map.of("eventTime",java.time.Instant.now().toString(),"body","Fixture")))).statusCode());
    }
}
