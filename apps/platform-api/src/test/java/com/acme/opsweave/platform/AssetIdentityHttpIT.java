package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","opsweave.inventory.store=memory","opsweave.auth.mode=dev","opsweave.auth.dev.token=asset-identity-http-fixture-token",
    "opsweave.auth.dev.tenant=asset-identity-http","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync",
    "opsweave.zabbix.mode=fixture","opsweave.inventory.cmdb-import-source=cmdb-import","opsweave.inventory.identity-namespace=enterprise-assets"
})
class AssetIdentityHttpIT {
    static final String TOKEN="asset-identity-http-fixture-token"; static final JsonMapper JSON=JsonMapper.builder().build();
    @LocalServerPort int port; final HttpClient http=HttpClient.newBuilder().build(); String entity; long version;
    @BeforeEach void seed() throws Exception {
        assertEquals(200,call("/api/v1/integrations/zabbix/hosts/sync","POST",null,true).statusCode());
        var page=json(call("/api/v1/entities/page?limit=25","GET",null,true));entity=page.get("items").get(0).get("id").asString(); refresh();
    }
    @AfterEach void close(){http.close();}
    HttpResponse<String> call(String path,String method,Object body,boolean auth) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)); if(auth)r.header("Authorization","Bearer "+TOKEN);
        if(body!=null)r.header("Content-Type","application/json");r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());assertTrue(r.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));return JSON.readTree(r.body());}
    void refresh() throws Exception {version=json(call("/api/v1/entities/"+entity,"GET",null,true)).get("version").asLong();}
    String path(){return "/api/v1/entities/"+entity+"/identity-keys";}
    Map<String,Object> claim(String key){return Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedNamespace","enterprise-assets","value",key,"reason","Verified authoritative asset register");}
    Map<String,Object> imported(JsonNode identity){return Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"externalId","asset-"+UUID.randomUUID(),"observedAt",Instant.now().toString(),"values",Map.of("owner","CMDB owner"),"mappingDigest",CmdbImportPipeline.DIGEST,"identity",Map.of("id",identity.get("id").asString(),"namespace",identity.get("namespace").asString(),"value",identity.get("value").asString(),"version",1));}
    @Test void claimResolvePinnedImportAndRevocationRejectsOldPendingReview() throws Exception {
        var key=UUID.randomUUID().toString();var command=claim(key);var response=call(path(),"POST",command,true);var receipt=json(response);assertEquals(command.get("requestId"),receipt.get("identity").get("id").asString());
        assertEquals(receipt,json(call(path(),"POST",command,true)));refresh();assertEquals(receipt.get("entityVersion").asLong(),version);
        var match=json(call("/api/v1/inventory/resolve-identity","POST",Map.of("value",key),true));assertEquals(entity,match.get("identity").get("entityId").asString());assertEquals(version,match.get("entityVersion").asLong());
        var review=json(call("/api/v1/entities/"+entity+"/source-reviews","POST",imported(match.get("identity")),true));assertEquals(key,review.get("identity").get("value").asString());
        var revoked=json(call(path()+"/"+receipt.get("identity").get("id").asString()+"/revocations","POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedNamespace","enterprise-assets","reason","Incorrect UUID verified"),true));assertEquals("REVOKED",revoked.get("identity").get("status").asString());refresh();
        assertEquals(404,call("/api/v1/inventory/resolve-identity","POST",Map.of("value",key),true).statusCode());
        assertEquals(409,call("/api/v1/entities/"+entity+"/source-reviews/"+review.get("id").asString()+"/decisions","POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedReviewVersion",1,"action","ACCEPT","choices",Map.of("owner","SUPPLEMENTAL"),"reason","Attempt stale identity"),true).statusCode());
    }
    @Test void conflictAndMalformedOrIdentityOverrideRequestsNeverMutate() throws Exception {
        long before=version;var key=UUID.randomUUID().toString();var command=claim(key);
        for(String field:List.of("tenantId","actor","namespace","permissions","entityId")){
            var body=new LinkedHashMap<>(command);body.put(field,"injected");assertEquals(400,call(path(),"POST",body,true).statusCode(),field);
        }
        var changedNamespace=new LinkedHashMap<>(command);changedNamespace.put("expectedNamespace","other-assets");
        assertEquals(409,call(path(),"POST",changedNamespace,true).statusCode());
        for(String invalid:List.of("Default string","10.0.0.1","00000000-0000-0000-0000-000000000000",key.toUpperCase()))assertEquals(400,call(path(),"POST",claim(invalid),true).statusCode());
        assertEquals(400,call(path()+"?limit=1&limit=2","GET",null,true).statusCode());assertEquals(401,call(path(),"GET",null,false).statusCode());refresh();assertEquals(before,version);
        json(call(path(),"POST",command,true));refresh();assertEquals(409,call(path(),"POST",claim(key),true).statusCode());assertEquals(before+1,version);
        assertEquals(400,call("/api/v1/inventory/resolve-identity","POST",Map.of("value",key,"tenantId","injected"),true).statusCode());
    }
    @Test void activeFieldDependencyMustBeExplicitlyRevertedBeforeRevocation() throws Exception {
        var key=UUID.randomUUID().toString();var identity=json(call(path(),"POST",claim(key),true)).get("identity");refresh();
        var review=json(call("/api/v1/entities/"+entity+"/source-reviews","POST",imported(identity),true));
        String decide="/api/v1/entities/"+entity+"/source-reviews/"+review.get("id").asString()+"/decisions";
        json(call(decide,"POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedReviewVersion",1,"action","ACCEPT","choices",Map.of("owner","SUPPLEMENTAL"),"reason","Confirmed authoritative source"),true));refresh();
        String revoke=path()+"/"+identity.get("id").asString()+"/revocations";
        assertEquals(409,call(revoke,"POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedNamespace","enterprise-assets","reason","Cannot revoke dependency"),true).statusCode());
        json(call(decide,"POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedReviewVersion",2,"action","REVOKE","choices",Map.of(),"reason","Undo field binding first"),true));refresh();
        assertEquals("REVOKED",json(call(revoke,"POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedEntityVersion",version,"expectedNamespace","enterprise-assets","reason","Now revoke identifier"),true)).get("identity").get("status").asString());
    }
}
