package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.*;
import com.acme.opsweave.platform.telemetry.VictoriaWorkflowMetricSink;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Real provider, trusted platform HTTP, PG and output engines. Only tagged synthetic observations are used. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1", "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.subject=replay-provider-fixture-author", "opsweave.inventory.store=postgres",
    "opsweave.zabbix.mode=closed",
    "opsweave.auth.dev.permissions=source.sync,source.configure,entity.read,entity.manage,metric.read,log.read,log.write,workflow.replay"
})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_REPLAY_PROVIDER_FILE",matches=".+")
class WorkflowReplayProviderHttpIT {
    static final String TOKEN="replay-provider-fixture-"+UUID.randomUUID();
    static final String TENANT="replay-provider-fixture-"+UUID.randomUUID();
    static final String FLOWS="/api/v1/integrations/workflows";
    static Path fixturePath,registry,keyring; static JsonNode fixture;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static void setup() {
        try {
            if(fixture!=null)return;
            fixturePath=Path.of(System.getenv("OPSWEAVE_TEST_REPLAY_PROVIDER_FILE")).toRealPath();
            Path checkout=Path.of(System.getProperty("user.dir")).toRealPath();
            while(checkout!=null&&!Files.exists(checkout.resolve(".git")))checkout=checkout.getParent();
            if(checkout==null||fixturePath.startsWith(checkout)||Files.size(fixturePath)>65536)throw new IllegalStateException();
            fixture=CatalogJson.JSON.readTree(Files.readString(fixturePath));
            if(!fixture.path("syntheticFixture").asBoolean()||!fixture.path("visible").asBoolean()
                ||!fixture.path("endpoint").asString().equals("http://127.0.0.1:18088/api_jsonrpc.php")
                ||fixture.path("metricCount").asInt()!=60||fixture.path("logCount").asInt()!=60)throw new IllegalStateException();
            registry=Files.writeString(Files.createTempFile("opsweave-replay-provider-",".json"),CatalogJson.JSON.writeValueAsString(Map.of(
                "schemaVersion","2.0","endpoints",List.of(Map.of("id","provider-replay","name","Synthetic Fixture local source","connectorKind","ZABBIX_HOST","address",fixture.get("endpoint").asString(),"tenants",List.of(TENANT))))));
            byte[] key=new byte[32];new SecureRandom().nextBytes(key);
            keyring=Files.writeString(Files.createTempFile("opsweave-replay-provider-keyring-",".json"),CatalogJson.JSON.writeValueAsString(Map.of("activeKeyId","acceptance","keys",Map.of("acceptance",Base64.getEncoder().encodeToString(key)))));
            Arrays.fill(key,(byte)0);
        } catch(Exception failure) { throw new IllegalStateException("Explicit private provider acceptance configuration is invalid"); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        setup();r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);
        r.add("opsweave.sources.endpoints-path",()->registry.toString());r.add("opsweave.credentials.keyring-path",()->keyring.toString());
        r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));
        r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        r.add("opsweave.metrics.victoria-url",()->System.getenv("OPSWEAVE_TEST_VM_URL"));
        r.add("opsweave.logs.url",()->System.getenv("OPSWEAVE_TEST_LOGS_URL"));
        r.add("opsweave.logs.username",()->System.getenv("OPSWEAVE_TEST_LOGS_USER"));
        r.add("opsweave.logs.password",()->System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));
    }
    @AfterAll static void cleanup() throws Exception {
        if(registry!=null)Files.deleteIfExists(registry);if(keyring!=null)Files.deleteIfExists(keyring);
    }
    HttpResponse<String> response(String path,String method,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+TOKEN);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode call(String path,String method,Object body) throws Exception {
        var result=response(path,method,body);assertEquals(200,result.statusCode(),"Trusted acceptance route: "+path);
        assertEquals("no-store",result.headers().firstValue("Cache-Control").orElse(""));
        assertFalse(result.body().contains(TOKEN));assertFalse(result.body().contains(fixture.get("token").asString()));
        return CatalogJson.JSON.readTree(result.body());
    }
    JsonNode get(String path) throws Exception { return call(path,"GET",null); }
    JsonNode post(String path,Object body) throws Exception { return call(path,"POST",body); }
    void export(String name,JsonNode body) throws Exception { Files.writeString(fixturePath.resolveSibling(name+".json"),body.toString()); }
    WorkflowDefinition publish(WorkflowDefinition.Source source,boolean log) throws Exception {
        WorkflowDefinition.Target target;
        Map<String,String> fields;
        if(log){target=new WorkflowDefinition.Target(null,1,null,"LOG");fields=Map.of("timestamp","eventTime","body","body");}
        else {
            var mapping=com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader()).find("zabbix",source.metric().sourceKey()).orElseThrow();
            target=new WorkflowDefinition.Target(null,1,null,"METRIC",mapping.pin(),mapping.metricKey());fields=Map.of("timestamp","timestamp","value","value","sourceKey","sourceKey");
        }
        var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("mapping",WorkflowDefinition.Type.MAP,"1",fields),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
        var definition=WorkflowOperators.builtIn().pin(new WorkflowDefinition((log?"log-":"metric-")+UUID.randomUUID(),1,"Synthetic Fixture provider replay",source,target,nodes,List.of(new WorkflowDefinition.Edge("source","mapping"),new WorkflowDefinition.Edge("mapping","validate"),new WorkflowDefinition.Edge("validate","output"))));
        var layout=new LinkedHashMap<String,Object>();for(var node:nodes)layout.put(node.id(),Map.of("x",180,"y",160*layout.size()));
        var saved=post(FLOWS+"/drafts",Map.of("definition",WorkflowJson.wire(definition),"layout",layout,"expectedEditVersion",0));
        assertEquals(definition.digest(),saved.get("digest").asString());
        var command=Map.of("id",definition.id(),"revision",1,"editVersion",saved.get("editVersion").asInt(),"digest",definition.digest(),"dryRun",true);
        var preview=post(FLOWS+"/preview",command);assertEquals("zabbix-jsonrpc",preview.get("receipt").get("origin").asString());
        assertTrue(preview.get("receipt").get("accepted").asInt()>0);assertFalse(preview.get("evaluation").get("writesPerformed").asBoolean());
        post(FLOWS+"/publish",Map.of("id",definition.id(),"revision",1,"editVersion",saved.get("editVersion").asInt(),"digest",definition.digest(),"previewId",preview.get("receipt").get("id").asString()));
        assertEquals(definition,WorkflowJson.decode(get(FLOWS+"/versions/"+definition.id()+"/1").toString()).definition());return definition;
    }
    Map<String,Object> command(WorkflowDefinition d) {
        var from=Instant.ofEpochSecond(fixture.get("from").asLong());
        return Map.of("requestId",UUID.randomUUID(),"id",d.id(),"revision",1,"digest",d.digest(),"from",from.toString(),"till",from.plusSeconds(60).toString());
    }
    Map<String,Object> execute(JsonNode plan) { return Map.of("requestId",UUID.randomUUID(),"planId",plan.get("requestId").asString(),"inputDigest",plan.get("proof").get("inputDigest").asString(),"batchDigest",plan.get("proof").get("batchDigest").asString()); }
    JsonNode confirm(String base,Map<String,Object> command) throws Exception {
        var receipt=post(base+"/execute",command);assertTrue(Set.of("CONFIRMED","UNKNOWN").contains(receipt.get("state").asString()));
        export(base.contains("log-replays")?"provider-log-initial-receipt":"provider-metric-initial-receipt",receipt);
        // Explicit, bounded test-only visibility probes. Product code has no automatic write/source retry.
        for(int i=0;i<30&&!receipt.get("state").asString().equals("CONFIRMED");i++){Thread.sleep(200);receipt=post(base+"/commands/"+command.get("requestId")+"/verification",null);}
        assertEquals("CONFIRMED",receipt.get("state").asString());return receipt;
    }
    @Test void registeredActualProviderCompletesBothProjectionChainsAndRejectsChangedInput() throws Exception {
        var credential=UUID.randomUUID();post("/api/v2/data-sources/credentials",Map.of("requestId",credential,"name","Synthetic Fixture provider credential","secret",fixture.get("token").asString()));
        var endpoint=get("/api/v2/data-sources/endpoints/provider-replay").get("endpoint");
        var connection=UUID.randomUUID();
        var created=post("/api/v2/data-sources/connections",Map.of("requestId",connection,"name","Synthetic Fixture replay connection","description","Actual local provider acceptance with tagged synthetic data","endpointPin",Map.of("id",endpoint.get("id").asString(),"digest",endpoint.get("digest").asString()),"credentialPin",new SourceCredential.Pin(credential,1,credential),"hostGroupIds",List.of("1001")));
        var instance=created.get("receipt").get("instance");String digest=instance.get("connectionDigest").asString(),sourcePath="/api/v2/data-sources/"+connection;
        var tested=post(sourcePath+"/test",Map.of("requestId",UUID.randomUUID(),"configurationRevision",1,"connectionDigest",digest));
        assertEquals("COMPLETED",tested.get("view").get("inspection").get("state").asString());assertEquals("zabbix-jsonrpc",tested.get("view").get("inspection").get("dataMode").asString());
        WorkflowDefinition.Source metricSource=null,logSource=null;UUID previous=null;int discoveries=0;
        for(;discoveries<50;discoveries++) {
            var id=UUID.randomUUID();var discovery=new LinkedHashMap<String,Object>();discovery.put("requestId",id);discovery.put("configurationRevision",1);discovery.put("connectionDigest",digest);discovery.put("previousRequestId",previous);
            var page=post(sourcePath+"/metric-discoveries",discovery).get("view").get("inspection");assertEquals("COMPLETED",page.get("state").asString());assertEquals("zabbix-jsonrpc",page.get("dataMode").asString());
            for(var row:get(sourcePath+"/connection/1/workflow-metrics").get("items"))if(row.get("source").get("metric").get("itemId").asString().equals(fixture.get("metricItemId").asString()))metricSource=WorkflowJson.source(row.get("source"));
            for(var row:get(sourcePath+"/connection/1/workflow-logs").get("items"))if(row.get("source").get("log").get("itemId").asString().equals(fixture.get("logItemId").asString()))logSource=WorkflowJson.source(row.get("source"));
            if(metricSource!=null&&logSource!=null)break;if(page.get("metricPage").get("complete").asBoolean())break;previous=id;
        }
        assertNotNull(metricSource,"Owned metric item must be discovered");assertNotNull(logSource,"Owned log item must be discovered");
        var metric=publish(metricSource,false);var log=publish(logSource,true);
        String metrics=FLOWS+"/metric-replays",logs=FLOWS+"/log-replays";
        var originalMetricTask=get(FLOWS+"/metric-streams/workflows/"+metric.id());var originalLogTask=get(FLOWS+"/log-streams/workflows/"+log.id());
        var metricCommand=command(metric);var metricPlan=post(metrics+"/plans",metricCommand);assertEquals("READY",metricPlan.get("state").asString());assertEquals(60,metricPlan.get("proof").get("inputCount").asInt());assertFalse(metricPlan.get("notifications").asBoolean());assertFalse(metricPlan.get("actions").asBoolean());
        var metricExecution=execute(metricPlan);var metricReceipt=confirm(metrics,metricExecution);var typedMetric=WorkflowMetricReplayJson.plan(metricPlan.toString());
        var metricSink=new VictoriaWorkflowMetricSink(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")),Duration.ofSeconds(5));var points=metricSink.read(typedMetric.proof().labels(),typedMetric.proof().timestamps());assertEquals(60,points.size());
        for(int i=0;i<60;i++)assertEquals(0,java.math.BigDecimal.valueOf(10+i,2).compareTo(points.get(i).value()));
        assertEquals("REPLAY_60S",typedMetric.proof().labels().get("collection_mode"));
        assertEquals(metricPlan,post(metrics+"/plans",metricCommand));assertEquals(metricReceipt,post(metrics+"/execute",metricExecution));
        assertEquals(metricReceipt,get(metrics+"/plans/"+metricPlan.get("requestId").asString()+"/execution").get("receipt"));
        var logCommand=command(log);var logPlan=post(logs+"/plans",logCommand);assertEquals("READY",logPlan.get("state").asString());assertEquals(60,logPlan.get("proof").get("inputCount").asInt());
        String records=logs+"/plans/"+logPlan.get("requestId").asString()+"/records";
        assertEquals(409,response(records,"GET",null).statusCode());
        var logExecution=execute(logPlan);var logReceipt=confirm(logs,logExecution);
        var first=get(records);assertTrue(first.get("complete").asBoolean());assertEquals(60,first.get("expectedRecords").asInt());assertEquals(50,first.get("records").size());assertEquals(49,first.get("nextIndex").asInt());
        var next=get(records+"/after/49");assertTrue(next.get("complete").asBoolean());assertEquals(10,next.get("records").size());assertTrue(next.get("nextIndex").isNull());
        var bodies=new ArrayList<JsonNode>();first.get("records").forEach(bodies::add);next.get("records").forEach(bodies::add);
        for(int i=0;i<60;i++){var row=bodies.get(i);assertEquals(i,row.get("index").asInt());assertEquals("  Synthetic Fixture replay "+i+" 🧵\n<script>untrusted()</script>  ",row.get("body").asString());assertTrue(row.get("severityText").isNull());}
        assertFalse(logPlan.toString().contains("Synthetic Fixture replay"));assertFalse(logReceipt.toString().contains("<script>"));
        assertEquals(logReceipt,get(logs+"/commands/"+logExecution.get("requestId")));assertEquals(logReceipt,post(logs+"/execute",logExecution));
        assertEquals(originalMetricTask,get(FLOWS+"/metric-streams/workflows/"+metric.id()));assertEquals(originalLogTask,get(FLOWS+"/log-streams/workflows/"+log.id()));
        export("provider-metric-plan",metricPlan);export("provider-metric-receipt",metricReceipt);export("provider-log-plan",logPlan);export("provider-log-receipt",logReceipt);export("provider-log-first-data",first);export("provider-log-next-data",next);
        // A late source observation invalidates a newly prepared scope, never the original confirmed projection.
        var changed=post(logs+"/plans",command(log));assertEquals("READY",changed.get("state").asString());
        var mutationFile=fixturePath.resolveSibling("provider-source-change.json");if(Files.exists(mutationFile))throw new IllegalStateException("Prior source mutation must be observed; use a fresh acceptance fixture");
        Files.writeString(mutationFile,"{\"attempted\":true}");
        var push=HttpRequest.newBuilder(URI.create(fixture.get("endpoint").asString())).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer "+fixture.get("token").asString()).POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(Map.of("jsonrpc","2.0","method","history.push","params",List.of(Map.of("itemid",fixture.get("logItemId").asString(),"clock",fixture.get("from").asLong()+1,"ns",100,"value","Synthetic Fixture late replay rejection")),"id",1)))).build();
        var pushed=http.send(push,HttpResponse.BodyHandlers.ofString());assertEquals(200,pushed.statusCode());var pushedBody=CatalogJson.JSON.readTree(pushed.body());assertFalse(pushedBody.has("error"));assertEquals("success",pushedBody.get("result").get("response").asString());assertFalse(pushedBody.get("result").get("data").get(0).has("error"));
        boolean changedVisible=false;
        for(int attempt=0;attempt<30;attempt++){
            var probe=HttpRequest.newBuilder(URI.create(fixture.get("endpoint").asString())).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer "+fixture.get("token").asString()).POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(Map.of("jsonrpc","2.0","method","history.get","params",Map.of("history",2,"itemids",List.of(fixture.get("logItemId").asString()),"time_from",fixture.get("from").asLong(),"time_till",fixture.get("from").asLong()+59,"output",List.of("clock","ns"),"limit",62),"id",1)))).build();
            var visible=http.send(probe,HttpResponse.BodyHandlers.ofString());assertEquals(200,visible.statusCode());var body=CatalogJson.JSON.readTree(visible.body());assertFalse(body.has("error"));changedVisible=body.get("result").size()==61;
            if(changedVisible)break;Thread.sleep(200);
        }
        assertTrue(changedVisible,"Explicit source visibility probes must observe the accepted change; no push retry");
        var rejected=post(logs+"/execute",execute(changed));assertEquals("FAILED",rejected.get("state").asString());assertEquals("SOURCE_WINDOW_CHANGED",rejected.get("error").asString());
        var rejectedData=get(logs+"/plans/"+changed.get("requestId").asString()+"/records");assertFalse(rejectedData.get("complete").asBoolean());assertEquals(0,rejectedData.get("records").size());
        assertEquals(logPlan,post(logs+"/plans",logCommand));assertEquals(logReceipt,post(logs+"/execute",logExecution));assertEquals(first.get("records"),get(records).get("records"));
        export("provider-source-change-receipt",rejected);
        var proof=CatalogJson.JSON.valueToTree(Map.of("actualProvider",true,"actualTrustedHttp",true,"actualPostgres",true,"actualMetricStore",true,"actualLogStore",true,"syntheticFixture",true,"metricRecords",60,"logRecords",60,"inputChangeRejected",true,"originalTasksPreserved",true));
        export("provider-proof",proof);
    }
}
