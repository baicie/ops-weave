package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=reorganization-http-local-test-token-only",
    "opsweave.auth.dev.subject=human-operator","opsweave.auth.dev.tenant=reorganization-http","opsweave.auth.dev.permissions=source.sync,entity.read,metric.read,incident.read,incident.manage,ai.diagnose,evidence.read",
    "opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=memory"})
class ReorganizationHttpIT {
    @LocalServerPort int port;final JsonMapper json=JsonMapper.builder().build();
    @Test void currentOwnershipHistoryAndEvidenceInvalidationAreExposedThroughAuthorizedHttp() throws Exception {
        ok(call("POST","/api/v1/integrations/zabbix/hosts/sync",null,true,null));
        var query=new LinkedHashMap<String,Object>();query.put("from",Instant.parse("2026-09-21T11:30:00Z").getEpochSecond());query.put("till",Instant.parse("2026-09-21T12:30:00Z").getEpochSecond());query.put("limit",25);query.put("afterEventId",null);
        ok(call("POST","/api/v1/integrations/zabbix/problems/ingest",query,true,null));
        var list=ok(call("GET","/api/v1/incidents",null,true,null)).get("items");String a=list.get(0).get("id").asString(),b=list.get(1).get("id").asString();
        var session=ok(call("POST","/api/v1/ai/read-sessions",Map.of("incidentId",b,"knowledgeMode","current","timeRange",Map.of("from","2026-09-21T11:30:00Z","to","2026-09-21T12:30:00Z")),true,null)).get("id").asString();
        var evidence=ok(call("POST","/api/v1/tools/incident.get/2.0.0",Map.of("incidentId",b),true,session)).get("data").get("evidence").get("id").asString();
        var request=new LinkedHashMap<String,Object>();request.put("requestKey",UUID.randomUUID().toString());request.put("kind","MERGE");request.put("sourceIncidentId",a);request.put("expectedSourceVersion",1);request.put("targetIncidentId",b);request.put("expectedTargetVersion",1);request.put("problemKeys",List.of());request.put("title",null);request.put("reason","Fixture manual review");
        assertEquals(401,call("POST","/api/v1/incidents/reorganizations",request,false,null).statusCode());
        request.put("tenantId","override");assertEquals(400,call("POST","/api/v1/incidents/reorganizations",request,true,null).statusCode());request.remove("tenantId");
        var merged=ok(call("POST","/api/v1/incidents/reorganizations",request,true,null));
        assertEquals(merged,ok(call("POST","/api/v1/incidents/reorganizations",request,true,null)));
        assertEquals(merged,ok(call("GET","/api/v1/incidents/reorganizations/"+request.get("requestKey"),null,true,null)));
        assertEquals(409,call("GET","/api/v1/ai/evidence/"+evidence,null,true,null).statusCode());
        var archive=ok(call("GET","/api/v1/incidents/"+a,null,true,null));assertEquals(b,archive.get("record").get("organization").get("mergedInto").asString());
        assertEquals(1,ok(call("GET","/api/v1/incidents",null,true,null)).get("items").size());
        var target=ok(call("GET","/api/v1/incidents/"+b,null,true,null));assertEquals(2,target.get("record").get("problems").size());
        assertEquals(0,ok(call("POST","/api/v1/integrations/zabbix/problems/ingest",query,true,null)).get("createdIncidents").asInt());
        String event=merged.get("change").get("movedProblems").get(0).get("problemEventId").asString();
        request.put("requestKey",UUID.randomUUID().toString());request.put("kind","SPLIT");request.put("sourceIncidentId",b);request.put("expectedSourceVersion",2);request.put("targetIncidentId",UUID.randomUUID().toString());request.put("expectedTargetVersion",0);request.put("problemKeys",List.of(Map.of("sourceInstanceId","zabbix-1","problemEventId",event)));request.put("title","New fixture investigation");
        var split=ok(call("POST","/api/v1/incidents/reorganizations",request,true,null));assertEquals(1,split.get("change").get("targetVersion").asInt());
        var page=ok(call("GET","/api/v1/incidents/"+b+"/reorganizations?limit=1",null,true,null));assertEquals(1,page.get("items").size());assertFalse(page.get("nextCursor").isNull());
        var next=ok(call("GET","/api/v1/incidents/"+b+"/reorganizations?limit=1&after="+page.get("nextCursor").asString(),null,true,null));assertEquals(1,next.get("items").size());assertTrue(next.get("nextCursor").isNull());
        var dir=Path.of("../../.tmp/reorganization-http");Files.createDirectories(dir);Files.writeString(dir.resolve("incident-reorganization-result.json"),merged.toString());Files.writeString(dir.resolve("incident-reorganization.json"),merged.get("change").toString());Files.writeString(dir.resolve("incident-reorganization-page.json"),page.toString());Files.writeString(dir.resolve("incident-detail.json"),archive.toString());
    }
    @Test void rejectsInvalidBoundsAndHiddenHistory() throws Exception {
        assertEquals(404,call("GET","/api/v1/incidents/reorganizations/"+UUID.randomUUID(),null,true,null).statusCode());
        assertEquals(400,call("GET","/api/v1/incidents/"+UUID.randomUUID()+"/reorganizations?limit=26",null,true,null).statusCode());
        assertEquals(400,call("GET","/api/v1/incidents/reorganizations/"+UUID.randomUUID()+"?tenantId=other",null,true,null).statusCode());
    }
    JsonNode ok(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return json.readTree(r.body());}
    HttpResponse<String> call(String method,String path,Object body,boolean auth,String session)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(18));if(auth)r.header("Authorization","Bearer reorganization-http-local-test-token-only");if(session!=null)r.header("X-OpsWeave-Read-Session",session);
        if(method.equals("POST"))r.header("Content-Type","application/json").POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else r.GET();
        return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
}
