package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.api.WorkflowLogWindowSink;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual trusted HTTP and PG. Source and lost output acknowledgement are explicitly protocol fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=recovery-http-fixture-only-32-characters","opsweave.auth.dev.subject=recovery-author","opsweave.auth.dev.permissions=entity.read,log.read,log.write,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowRecoveryHttpIT {
    static final String TENANT="recovery-http-fixture-"+UUID.randomUUID(),ROOT="/api/v1/integrations/workflows/recovery";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,boolean auth,String method,String body)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ROOT+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer recovery-http-fixture-only-32-characters");if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    static class Time extends Clock {Instant value=Instant.now().minusSeconds(11).truncatedTo(java.time.temporal.ChronoUnit.MICROS);public Instant instant(){return value;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
    WorkflowRecovery.Command original(){
        var tenant=new TenantId(TENANT);var p=new Principal(new SubjectId("recovery-author"),tenant,Set.of(Permission.SOURCE_SYNC,Permission.LOG_READ,Permission.LOG_WRITE,Permission.ENTITY_READ),ResourceScope.tenantWide());var clock=new Time();var sourceId=UUID.randomUUID();var src=new WorkflowDefinition.Source("ZABBIX_LOG",sourceId.toString(),new WorkflowDefinition.ConfigurationPin(sourceId,1,"sha256:"+"a".repeat(64)),null,WorkflowLogSourcePin.from(UUID.randomUUID(),new SourceMetricDiscovery.Item("1","2","log[/synthetic-fixture]","Synthetic Fixture","","LOG","NO_MAPPING",null)));
        var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",Map.of("timestamp","eventTime","body","body")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition("recovery-fixture-"+UUID.randomUUID().toString().substring(0,8),1,"Synthetic Fixture",src,new WorkflowDefinition.Target(null,1,null,"LOG"),nodes,List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output"))));var layout=new HashMap<String,WorkflowStore.Position>();for(var n:nodes)layout.put(n.id(),new WorkflowStore.Position(0,0));wiring.workflows().transaction(tenant,s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,clock.instant(),null),p.subjectId().value());return null;});
        var guards=new WorkflowService.Sources(){public void require(Principal who,WorkflowDefinition.Source source,WorkflowStore.Session s,boolean available){}public void requireTarget(Principal who,WorkflowDefinition.Source source,WorkflowDefinition.Target target,WorkflowStore.Session s,boolean available){require(who,source,s,available);}};var flows=new WorkflowService(wiring.workflows(),(who,target)->{throw new AssertionError("No model");},(who,source,id)->{throw new AssertionError("No preview IO");},guards,clock);var sink=new WorkflowLogWindowSink(){public boolean ready(){return true;}public void write(WorkflowLogWindow.Batch b){throw new WorkflowLogOutputService.OutputFailure(true);}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope scope){return List.of();}};var runtime=new WorkflowLogStreamService(wiring.workflows(),flows,(who,source,from,till)->{var row=new LinkedHashMap<String,Object>();row.put("timestamp",from.plusSeconds(1).toString());row.put("body","Synthetic Fixture");row.put("sourceKey",source.log().sourceKey());row.put("severityCode","0");row.put("eventId","0");row.put("logEventTime",null);row.put("eventSource",null);return List.of(row);},sink,new Semaphore(2),clock);runtime.command(p,new WorkflowLogStream.Command(UUID.randomUUID(),d.id(),1,d.digest(),0,WorkflowLogStream.Operation.START),()->null);clock.value=clock.value.plusSeconds(11);runtime.tick(p);var task=runtime.status(p,d.id()).task();assertEquals("OUTPUT_UNCONFIRMED",task.error());return new WorkflowRecovery.Command(UUID.randomUUID(),d.id(),1,d.digest(),WorkflowQuality.Kind.LOG_STREAM,task.pendingBatchId(),1,true);
    }
    @Test void closureAndOriginalReceiptAreIdempotentAtTrustedHttpBoundary()throws Exception{var c=original();var body=CatalogJson.JSON.writeValueAsString(c);var r=call("/abandon",true,"POST",body);assertEquals(200,r.statusCode(),r.body());assertEquals("ABANDONED",CatalogJson.JSON.readTree(r.body()).get("state").asString());assertEquals(r.body(),call("/abandon",true,"POST",body).body());assertEquals(r.body(),call("/commands/"+c.requestId(),true,"GET",null).body());var changed=body.replace(c.batchId().toString(),UUID.randomUUID().toString());assertEquals(409,call("/abandon",true,"POST",changed).statusCode());}
    @Test void acknowledgementAndClientScopeForgeryFailBeforeClosure()throws Exception{var c=original();var body=CatalogJson.JSON.writeValueAsString(c);assertEquals(400,call("/abandon",true,"POST",body.replace(":true",":false")).statusCode());assertEquals(400,call("/abandon",true,"POST",body.replaceFirst("\\{","{\"tenantId\":\"forged\"," )).statusCode());assertEquals(401,call("/abandon",false,"POST",body).statusCode());assertEquals(404,call("/commands/"+c.requestId(),true,"GET",null).statusCode());}
    @Test void malformedLookupForgedQueryAndMutationOfReceiptFailClosed()throws Exception{assertEquals(400,call("/commands/1-1-1-1-1",true,"GET",null).statusCode());assertEquals(400,call("/commands/"+UUID.randomUUID()+"?tenantId=forged",true,"GET",null).statusCode());for(var method:List.of("POST","PUT","PATCH","DELETE"))assertEquals(405,call("/commands/"+UUID.randomUUID(),true,method,null).statusCode());}
}
