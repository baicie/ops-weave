package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.ai.AiRetentionJson;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=retention-fixture-token-not-for-real-use","opsweave.auth.dev.subject=retention-operator","opsweave.auth.dev.permissions=ai.retention.manage","opsweave.inventory.store=postgres","opsweave.zabbix.mode=fixture"})
class AiRetentionHttpIT {
    static final String TENANT="retention-http-"+UUID.randomUUID();static final Path FILE=file();@LocalServerPort int port;
    static Path file(){try{return Files.createTempFile("opsweave-retention-fixture-",".json");}catch(Exception e){throw new IllegalStateException();}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.ai.retention-policies-file",FILE::toString);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @BeforeEach void policy()throws Exception{Files.writeString(FILE,AiRetentionJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","policies",List.of(Map.of("tenantId",TENANT,"version","fixture-v1","insightDays",30,"evidenceDays",7,"auditDays",90,"batchSize",100,"heldIncidents",List.of(),"allowPurge",true)))));}
    @AfterAll static void cleanup()throws Exception{Files.deleteIfExists(FILE);}
    HttpResponse<String> call(String path,Object body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/ai/retention"+path)).timeout(Duration.ofSeconds(20));if(auth)b.header("Authorization","Bearer retention-fixture-token-not-for-real-use");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body instanceof String s?s:AiRetentionJson.JSON.writeValueAsString(body)));return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode ok(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElseThrow());return AiRetentionJson.JSON.readTree(r.body());}
    Map<String,Object> command(JsonNode p){return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"policyDigest",p.get("policyDigest").asString(),"asOf",p.get("asOf").asString(),"previewDigest",p.get("previewDigest").asString()));}
    @Test void realPgPreviewExplicitCommitAndReceiptReplay()throws Exception{var preview=ok(call("",null,true));var cmd=command(preview.get("preview"));var receipt=ok(call("/runs",cmd,true));assertEquals("COMPLETED",receipt.get("state").asString());assertEquals(receipt,ok(call("/runs",cmd,true)));Files.writeString(FILE,"{}");assertEquals(receipt,ok(call("/runs/"+cmd.get("requestId"),null,true)));assertEquals(receipt,ok(call("/runs",cmd,true)));var artifacts=Path.of("../../.tmp/retention-http");Files.createDirectories(artifacts);Files.writeString(artifacts.resolve("ai-retention-preview.json"),preview.toString());Files.writeString(artifacts.resolve("ai-retention-receipt.json"),receipt.toString());}
    @Test void changedPolicyTamperedPreviewAndIdentityOverridesFailClosed()throws Exception{var cmd=command(ok(call("",null,true)).get("preview"));cmd.put("tenantId","other");assertEquals(400,call("/runs",cmd,true).statusCode());cmd.remove("tenantId");cmd.put("previewDigest","sha256:"+"f".repeat(64));assertEquals(409,call("/runs",cmd,true).statusCode());var original=command(ok(call("",null,true)).get("preview"));Files.writeString(FILE,Files.readString(FILE).replace("fixture-v1","fixture-v2"));assertEquals(409,call("/runs",original,true).statusCode());assertEquals(400,call("?tenantId=other",null,true).statusCode());assertEquals(400,call("/runs","{\"requestId\":\"a\",\"requestId\":\"b\"}",true).statusCode());}
    @Test void missingIdentityInvalidConfigurationAndDryRunOnlyCannotDelete()throws Exception{assertEquals(401,call("",null,false).statusCode());Files.writeString(FILE,Files.readString(FILE).replace("\"allowPurge\":true","\"allowPurge\":false"));var cmd=command(ok(call("",null,true)).get("preview"));assertEquals(403,call("/runs",cmd,true).statusCode());Files.writeString(FILE,"{}");assertEquals(503,call("",null,true).statusCode());assertEquals(404,call("/runs/"+UUID.randomUUID(),null,true).statusCode());}
}
