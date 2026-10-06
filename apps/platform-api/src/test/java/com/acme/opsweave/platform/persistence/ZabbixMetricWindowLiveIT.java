package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricStream.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.platform.telemetry.VictoriaWorkflowMetricSink;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual local provider, PostgreSQL and time-series sink; values are explicit Synthetic Fixtures. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_WINDOW_FIXTURE",matches=".+")
class ZabbixMetricWindowLiveIT extends PostgresWorkflowMetricStreamIT {
    @Test void actualProviderPagesThreeHundredPointsAndConfirmsOnlyTheNewLatePoint()throws Exception{
        var fixturePath=Path.of(System.getenv("OPSWEAVE_TEST_WINDOW_FIXTURE"));var f=CatalogJson.JSON.readTree(Files.readString(fixturePath));assertTrue(f.get("syntheticFixture").asBoolean());assertTrue(f.get("ready").asBoolean());
        var endpoint=URI.create(f.get("endpoint").asString());assertEquals("127.0.0.1",endpoint.getHost());assertEquals("/api_jsonrpc.php",endpoint.getPath());String token=System.getenv("OPSWEAVE_TEST_WINDOW_TOKEN"),itemId=f.get("itemId").asString();assertNotNull(token);
        var mapping=ClasspathMappingCatalog.load(getClass().getClassLoader()).find("zabbix","system.cpu.util[,user]").orElseThrow();var item=new SourceMetricDiscovery.Item(itemId,f.get("hostId").asString(),mapping.itemKeyExact(),"Synthetic Fixture CPU window","%","FLOAT","MAPPED",SourceMetricDiscovery.Mapping.from(mapping));
        var pin=new WorkflowDefinition.Source("ZABBIX_METRIC","synthetic-window-source",new WorkflowDefinition.ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"a".repeat(64)),WorkflowMetricSourcePin.from(UUID.randomUUID(),item));var published=prepare(pin);
        var properties=new OpsweaveProperties(new OpsweaveProperties.Auth("dev",true,new OpsweaveProperties.Auth.Dev("fixture-token-only-for-local-setup",owner,tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","","",1),new OpsweaveProperties.Inventory("postgres","","",""));
        var real=new JacksonZabbixTransport().registered(endpoint,properties);var historyCalls=new int[1];var bounded=new ZabbixJsonRpcConnector.Transport(){public String exchange(URI uri,String body,String secret){return exchangeWithin(uri,body,secret,Duration.ofSeconds(10));}public String exchangeWithin(URI uri,String body,String secret,Duration remaining){if(body.contains("\"history.get\""))historyCalls[0]++;return real.exchangeWithin(uri,body,secret,remaining);}public List<Map<String,Object>> readHostArray(String body){return real.readHostArray(body);}};
        var reader=new ZabbixWorkflowMetricReader();var clock=new Time();clock.now=Instant.ofEpochSecond(f.get("from").asLong()+60);var sink=new VictoriaWorkflowMetricSink(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")),Duration.ofSeconds(5));
        WorkflowMetricStreamService.Source input=(p,source,from,till)->reader.readWindow(endpoint,bounded,token,source.metric(),from,till,clock.instant());var service=service(wiring.workflows(),sink,clock,input);service.command(p,new Command(UUID.randomUUID(),published.workflowId(),1,published.digest(),0,Operation.START),()->null);clock.advance(11);service.tick(p);var initial=verifyReadOnly(service,published.workflowId());assertEquals(300,initial.task().confirmedPoints(),initial.task().error());assertTrue(historyCalls[0]>=3);var original=initial.batches().getFirst();var cursor=initial.task().cursor();
        var body=Map.of("jsonrpc","2.0","method","history.push","params",List.of(Map.of("itemid",itemId,"clock",f.get("from").asLong()+45,"ns",500000000,"value","20")),"id",1);var http=HttpClient.newHttpClient();var pushed=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,pushed.statusCode());var result=CatalogJson.JSON.readTree(pushed.body()).get("result");assertNotNull(result);assertEquals("success",result.get("response").asString());assertFalse(result.get("data").get(0).has("error"));
        List<Map<String,Object>> sourceRows=List.of();for(int i=0;i<30;i++){sourceRows=reader.readWindow(endpoint,bounded,token,pin.metric(),original.from(),original.till(),clock.instant());if(sourceRows.size()==301)break;Thread.sleep(500);}assertEquals(301,sourceRows.size());clock.advance(60);if(initial.task().state().equals("STOPPED"))service.command(p,new Command(UUID.randomUUID(),published.workflowId(),1,published.digest(),initial.task().generation(),Operation.RESUME),()->null);service.tick(p);var current=verifyReadOnly(service,published.workflowId());assertEquals(301,current.task().confirmedPoints(),current.task().error());assertEquals(1,current.task().confirmedWindows());assertEquals(cursor,current.task().cursor());var late=current.batches().getFirst();assertEquals(original.id(),late.reconcilesBatchId());assertEquals(1,late.latePoints());assertEquals(301,sink.read(late.labels(),late.timestamps()).size());assertEquals(original,wiring.workflows().transaction(tenant,s->s.metricStreamBatch(owner,original.id()).orElseThrow()));
        var proof=CatalogJson.JSON.createObjectNode();proof.put("actualProvider",true);proof.put("syntheticFixture",true);proof.put("confirmedPoints",301);proof.put("latePoints",1);proof.put("cursorPreserved",true);proof.put("originalProofPreserved",true);proof.put("historyRequests",historyCalls[0]);proof.set("status",CatalogJson.JSON.valueToTree(current));Files.writeString(fixturePath.resolveSibling("window121-provider-proof.json"),proof.toString());
    }
}
