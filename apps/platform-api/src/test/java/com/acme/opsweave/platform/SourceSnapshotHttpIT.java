package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.platform.inventory.SourceBindingCorrectionJson;
import com.acme.opsweave.sharedkernel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=snapshot-http-local-test-only-32-characters","opsweave.auth.dev.subject=snapshot-user","opsweave.auth.dev.tenant=tenant-snapshot-http","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres","opsweave.inventory.cmdb-import-source=cmdb-snapshot-http","opsweave.inventory.identity-namespace=enterprise-assets"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceSnapshotHttpIT {
    static final String TENANT="snapshot-http-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();final String root="/api/v1/integrations/cmdb/snapshots";
    HttpResponse<String> call(String path,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer snapshot-http-local-test-only-32-characters");return http.send(body==null?b.GET().build():b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    @Test void importsReadsOriginalReceiptAndAuthorizedPresenceWithExplicitReview()throws Exception {
        var tenant=new TenantId(TENANT);var now=Instant.now().minusSeconds(2);String key=UUID.randomUUID().toString();var host=new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-snapshot-http",Map.of("hostid",key,"host","primary","status","0"),now,now,"raw-"+key);wiring.writer().upsert(host.entity(),host.observation(),host.link());
        UUID claimId=UUID.randomUUID();String asset=UUID.randomUUID().toString();wiring.assetIdentities().change(tenant,host.entity().id(),"enterprise-assets",new AssetIdentity.Command(claimId,AssetIdentity.Action.ASSERT,claimId,1,asset,"snapshot-user","Verified test identity"),now);
        var config=call(root+"/config",null,true);assertEquals(200,config.statusCode());UUID requestId=UUID.randomUUID();
        var body=Map.of("mappingDigest",CmdbImportPipeline.DIGEST,"input",Map.of("requestId",requestId.toString(),"observedAt",now.toString(),"complete",false,"records",List.of(Map.of("externalId","asset-"+asset,"assetUuid",asset,"values",Map.of("owner","Review me")))));
        String encoded=SourceReviewJson.JSON.writeValueAsString(body);var reply=call(root,encoded,true);assertEquals(200,reply.statusCode(),reply.body());
        assertEquals(SourceReviewJson.JSON.readTree(reply.body()),SourceReviewJson.JSON.readTree(call(root,encoded,true).body()));assertEquals(SourceReviewJson.JSON.readTree(reply.body()),SourceReviewJson.JSON.readTree(call(root+"/"+requestId,null,true).body()));
        var presence=call("/api/v1/entities/"+host.entity().id().value()+"/source-presence",null,true);assertEquals(200,presence.statusCode(),presence.body());assertEquals("PRESENT",SourceReviewJson.JSON.readTree(presence.body()).get("items").get(0).get("status").asString());
        assertNull(wiring.query().find(tenant,host.entity().id()).orElseThrow().attributes().get("owner"));
        assertEquals(401,call(root+"/"+requestId,null,false).statusCode());assertEquals(400,call(root+"/"+requestId+"?tenantId=other",null,true).statusCode());
        assertEquals(404,call("/api/v1/entities/"+UUID.randomUUID()+"/source-presence",null,true).statusCode());
        var dir=java.nio.file.Path.of("../../.tmp/source-snapshot-http");java.nio.file.Files.createDirectories(dir);
        for(var entry:Map.of("source-snapshot-config",config.body(),"source-snapshot-input",encoded,"source-snapshot-receipt",reply.body(),"source-presence-page",presence.body()).entrySet())java.nio.file.Files.writeString(dir.resolve(entry.getKey()+".json"),entry.getValue());
    }
    @Test void identityOverridesDuplicateKeysMalformedAndOversizedSnapshotsFailClosed()throws Exception {
        assertEquals(400,call(root,"{\"input\":{},\"tenantId\":\"other\",\"mappingDigest\":\"x\"}",true).statusCode());
        assertEquals(400,call(root,"{\"input\":{},\"input\":{},\"mappingDigest\":\"x\"}",true).statusCode());
        assertEquals(400,call(root,"{\"padding\":\""+"x".repeat(131073)+"\"}",true).statusCode());
        assertEquals(400,call(root,"[]",true).statusCode());assertEquals(400,call(root+"/config?sourceInstanceId=other",null,true).statusCode());
        assertEquals(400,call(root,"{} {}",true).statusCode());
        assertEquals(404,call(root+"/"+UUID.randomUUID(),null,true).statusCode());
    }
    record CorrectionSeed(EntityId entity,AssetIdentity.Pin pin){}
    CorrectionSeed correctionSeed(TenantId tenant,Instant at){String key=UUID.randomUUID().toString();var host=new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-correction-http",Map.of("hostid",key,"host","Correction fixture","status","0"),at,at,"raw-"+key);wiring.writer().upsert(host.entity(),host.observation(),host.link());var id=UUID.randomUUID();var r=wiring.assetIdentities().change(tenant,host.entity().id(),"enterprise-assets",new AssetIdentity.Command(id,AssetIdentity.Action.ASSERT,id,1,UUID.randomUUID().toString(),"snapshot-user","Verified test target"),at);return new CorrectionSeed(host.entity().id(),r.identity().pin());}
    @Test void correctsFreshObservationAndReadsActorReceiptAndBothEntityHistories()throws Exception{
        var tenant=new TenantId(TENANT);var at=Instant.now().minusSeconds(2);var from=correctionSeed(tenant,at);var to=correctionSeed(tenant,at);String external="correction-"+UUID.randomUUID();
        var before=wiring.sourceSnapshots().ingest(tenant,"snapshot-user","cmdb-snapshot-http","enterprise-assets",new SourceSnapshot.Input(UUID.randomUUID(),at,false,List.of(new SourceSnapshot.Row(external,from.pin().value(),Map.of("owner","Old unreviewed")))),CmdbImportPipeline.DIGEST);
        var command=new SourceBindingCorrection.Command(UUID.randomUUID(),external,before.input().requestId(),from.entity(),3,to.entity(),2,to.pin(),Instant.now().minusMillis(100),Map.of("owner","New unreviewed"),"Verified source target; keep history");
        var body=SourceReviewJson.JSON.writeValueAsString(Map.of("command",SourceBindingCorrectionJson.command(command),"mappingDigest",CmdbImportPipeline.DIGEST));String endpoint="/api/v1/integrations/cmdb/binding-corrections";
        var response=call(endpoint,body,true);assertEquals(200,response.statusCode(),response.body());var receipt=SourceBindingCorrectionJson.receipt(response.body());assertEquals(command,receipt.command());
        assertEquals(SourceReviewJson.JSON.readTree(response.body()),SourceReviewJson.JSON.readTree(call(endpoint,body,true).body()));assertEquals(SourceReviewJson.JSON.readTree(response.body()),SourceReviewJson.JSON.readTree(call(endpoint+"/"+command.requestId(),null,true).body()));
        String history="/api/v1/entities/"+from.entity().value()+"/source-binding-corrections";var page=call(history+"?limit=10",null,true);assertEquals(200,page.statusCode(),page.body());assertEquals(1,SourceReviewJson.JSON.readTree(page.body()).get("items").size());
        assertEquals(200,call("/api/v1/entities/"+to.entity().value()+"/source-binding-corrections",null,true).statusCode());assertEquals(400,call(history+"?limit=26",null,true).statusCode());assertEquals(400,call(history+"?limit=1&limit=2",null,true).statusCode());assertEquals(400,call(history+"?tenantId=other",null,true).statusCode());
        assertEquals(401,call(endpoint+"/"+command.requestId(),null,false).statusCode());assertEquals(404,call(endpoint+"/"+UUID.randomUUID(),null,true).statusCode());
        assertNull(wiring.query().find(tenant,to.entity()).orElseThrow().attributes().get("owner"));assertEquals(from.entity(),wiring.sourceSnapshots().receipt(tenant,"snapshot-user","cmdb-snapshot-http","enterprise-assets",before.input().requestId()).orElseThrow().resolved().getFirst().entityId());
        var dir=java.nio.file.Path.of("../../.tmp/source-correction-http");java.nio.file.Files.createDirectories(dir);for(var e:Map.of("source-binding-correction-input",body,"source-binding-correction-receipt",response.body(),"source-binding-correction-page",page.body()).entrySet())java.nio.file.Files.writeString(dir.resolve(e.getKey()+".json"),e.getValue());
    }
    @Test void correctionRejectsMalformedIdentityOverridesAndOverBudgetBodies()throws Exception{
        String endpoint="/api/v1/integrations/cmdb/binding-corrections";
        for(String body:List.of("[]","{} {}","{\"command\":{},\"command\":{},\"mappingDigest\":\"x\"}","{\"tenantId\":\"other\",\"command\":{},\"mappingDigest\":\"x\"}","{\"padding\":\""+"x".repeat(16385)+"\"}"))assertEquals(400,call(endpoint,body,true).statusCode());
        assertEquals(400,call(endpoint+"?sourceInstanceId=other","{}",true).statusCode());assertEquals(404,call("/api/v1/entities/"+UUID.randomUUID()+"/source-binding-corrections",null,true).statusCode());
    }
}
