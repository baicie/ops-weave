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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=source-review-test-only-token-not-for-use","opsweave.auth.dev.subject=review-user","opsweave.auth.dev.tenant=tenant-review",
    "opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1",
    "opsweave.inventory.store=memory","opsweave.inventory.cmdb-import-source=cmdb-import-dev"})
class SourceReviewHttpIT {
    @LocalServerPort int port;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpResponse<String> call(String path, Object body, boolean authorized) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(authorized)r.header("Authorization","Bearer source-review-test-only-token-not-for-use");
        if(path.endsWith("/sync"))r.POST(HttpRequest.BodyPublishers.noBody());
        else if(body==null)r.GET();else r.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    String entity() throws Exception {
        assertEquals(200,call("/api/v1/integrations/zabbix/hosts/sync",Map.of(),true).statusCode());
        return json.readTree(call("/api/v1/entities/page?q=Zabbix",null,true).body()).get("items").get(0).get("id").asString();
    }
    @Test void fullHumanWorkflowUsesTrustedActorAndKeepsOriginalReceipts() throws Exception {
        String id=entity(), base="/api/v1/entities/"+id, path=base+"/source-reviews";
        var e=json.readTree(call(base,null,true).body()); String original=e.get("name").asString(); long version=e.get("version").asLong();
        var page=json.readTree(call(path,null,true).body()); String digest=page.get("mapping").get("digest").asString();
        String requestId=UUID.randomUUID().toString();
        var input=Map.of("requestId",requestId,"expectedEntityVersion",version,"externalId","http-import","observedAt",Instant.now().minusSeconds(1).toString(),"mappingDigest",digest,"values",Map.of("name","CMDB confirmed host","owner","human imported owner"));
        var staged=call(path,input,true); assertEquals(200,staged.statusCode(),staged.body()); assertEquals("review-user",json.readTree(staged.body()).get("actor").asString());
        assertEquals(original,json.readTree(call(base,null,true).body()).get("name").asString());
        assertEquals(json.readTree(staged.body()),json.readTree(call(path,input,true).body()));
        var accept=Map.of("requestId",UUID.randomUUID().toString(),"action","ACCEPT","expectedEntityVersion",version,"expectedReviewVersion",1,"choices",Map.of("name","SUPPLEMENTAL","owner","SUPPLEMENTAL"),"reason","trusted human checked the external object");
        var receipt=call(path+"/"+requestId+"/decisions",accept,true); assertEquals(200,receipt.statusCode(),receipt.body());
        assertEquals("CMDB confirmed host",json.readTree(call(base,null,true).body()).get("name").asString());
        assertEquals(200,call("/api/v1/integrations/zabbix/hosts/sync",Map.of(),true).statusCode());
        var current=json.readTree(call(base,null,true).body()); assertEquals("CMDB confirmed host",current.get("name").asString());
        var revoke=Map.of("requestId",UUID.randomUUID().toString(),"action","REVOKE","expectedEntityVersion",current.get("version").asLong(),"expectedReviewVersion",2,"choices",Map.of(),"reason","restore latest primary source");
        var revoked=call(path+"/"+requestId+"/decisions",revoke,true); assertEquals(200,revoked.statusCode(),revoked.body());
        assertEquals(original,json.readTree(call(base,null,true).body()).get("name").asString());
        assertEquals(json.readTree(receipt.body()),json.readTree(call(path+"/"+requestId+"/decisions",accept,true).body()));
        var changed=new HashMap<String,Object>(accept);changed.put("reason","different");assertEquals(409,call(path+"/"+requestId+"/decisions",changed,true).statusCode());
        var out=Path.of("../../.tmp/source-review-http");Files.createDirectories(out);
        Files.writeString(out.resolve("source-review.json"),revoked.body());Files.writeString(out.resolve("source-review-page.json"),call(path,null,true).body());
        Files.writeString(out.resolve("source-review-import.json"),json.writeValueAsString(input));Files.writeString(out.resolve("source-review-decision.json"),json.writeValueAsString(accept));
        Files.writeString(out.resolve("cmdb-import-pipeline.json"),page.get("mapping").toString());
        assertEquals(401,call(path,null,false).statusCode());
    }
    @Test void rejectsInjectedIdentityUnboundedBodiesInvalidMappingAndQuery() throws Exception {
        String id=entity(), path="/api/v1/entities/"+id+"/source-reviews";
        var e=json.readTree(call("/api/v1/entities/"+id,null,true).body());
        for(String field:List.of("tenantId","actor","permissions","sourceInstanceId")) {
            var input=Map.of(field,"untrusted");assertEquals(400,call(path,input,true).statusCode());
        }
        var input=Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",e.get("version").asLong(),"externalId","bad-mapping","observedAt",Instant.now().toString(),"mappingDigest","sha256:"+"0".repeat(64),"values",Map.of("owner","ops"));
        assertEquals(409,call(path,input,true).statusCode());assertEquals(400,call(path,Map.of("values",Map.of("owner","x".repeat(17000))),true).statusCode());
        for(String suffix:List.of("?limit=0","?limit=26","?after=../raw","?tenantId=other","?limit=1&limit=2"))assertEquals(400,call(path+suffix,null,true).statusCode());
        assertEquals(404,call("/api/v1/entities/00000000-0000-0000-0000-000000000000/source-reviews",null,true).statusCode());
    }
}
