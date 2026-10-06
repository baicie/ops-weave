package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Local HTTP/PG integration. No external source or model calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev", "opsweave.auth.dev.token=catalog-http-fixture-only-32-characters", "opsweave.auth.dev.subject=catalog-author", "opsweave.auth.dev.permissions=entity.read,entity.manage", "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class ModelCatalogHttpIT {
    static final String TENANT = "catalog-http-" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("opsweave.auth.dev.tenant", () -> TENANT);
        r.add("opsweave.inventory.jdbc-url", () -> System.getenv("OPSWEAVE_TEST_JDBC_URL")); r.add("opsweave.inventory.jdbc-user", () -> System.getenv("OPSWEAVE_TEST_JDBC_USER")); r.add("opsweave.inventory.jdbc-password", () -> System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> call(String path, String body, boolean auth) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/catalog" + path)).timeout(Duration.ofSeconds(15));
        if (auth) b.header("Authorization", "Bearer catalog-http-fixture-only-32-characters");
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); else b.GET();
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    String definition() { return """
        {"schemaVersion":"1.0","id":"custom.http_test","revision":1,"kind":"ENTITY","label":"HTTP fixture","description":"Test only","cleaningProfile":"safe-scalars-v1","fields":[{"id":"name","label":"Name","type":"TEXT","required":true,"maxLength":255},{"id":"port","label":"Port","type":"INTEGER","required":true,"min":1,"max":65535}]}
        """.strip(); }
    @Test void builtinsAreDefinitionsAndHttpPersistenceIsImmutable() throws Exception {
        var catalog = call("", null, true); assertEquals(200, catalog.statusCode(), catalog.body());
        assertEquals("no-store", catalog.headers().firstValue("Cache-Control").orElse(""));
        var page = CatalogJson.JSON.readTree(catalog.body()); assertEquals(9, page.get("package").get("definitions").size()); assertEquals(3, page.get("package").get("metrics").size()); assertEquals("postgres", page.get("storage").asString());
        var saved = call("/drafts", "{\"definition\":" + definition() + ",\"expectedEditVersion\":0}", true); assertEquals(200, saved.statusCode(), saved.body());
        var draft = CatalogJson.JSON.readTree(saved.body()); assertEquals(1, draft.get("editVersion").asInt());
        assertEquals(409, call("/drafts", "{\"definition\":" + definition() + ",\"expectedEditVersion\":0}", true).statusCode());
        String command = "{\"ref\":{\"id\":\"custom.http_test\",\"revision\":1},\"expectedEditVersion\":1,\"digest\":\"" + draft.get("digest").asString() + "\"}";
        var published = call("/publish", command, true); assertEquals(200, published.statusCode(), published.body());
        assertEquals(CatalogJson.JSON.readTree(published.body()), CatalogJson.JSON.readTree(call("/publish", command, true).body()));
        assertEquals(CatalogJson.JSON.readTree(published.body()), CatalogJson.JSON.readTree(call("/versions/custom.http_test/1", null, true).body()));
        assertEquals(409, call("/drafts", "{\"definition\":" + definition() + ",\"expectedEditVersion\":1}", true).statusCode());
    }
    @Test void strictScalarPreviewPreservesMissingAndNullWithoutWriting() throws Exception {
        var before = CatalogJson.JSON.readTree(call("", null, true).body()).get("drafts");
        var good = call("/preview", "{\"definition\":" + definition() + ",\"sample\":{\"name\":\" svc \",\"port\":\"8080\"}}", true);
        assertEquals(200, good.statusCode(), good.body()); var body = CatalogJson.JSON.readTree(good.body()); assertTrue(body.get("valid").asBoolean()); assertEquals("svc", body.get("values").get("name").asString()); assertEquals(8080, body.get("values").get("port").asInt());
        var bad = call("/preview", "{\"definition\":" + definition() + ",\"sample\":{\"name\":null,\"extra\":\"keep in source\"}}", true);
        assertEquals(200, bad.statusCode()); assertFalse(CatalogJson.JSON.readTree(bad.body()).get("valid").asBoolean()); assertTrue(bad.body().contains("MISSING_REQUIRED")); assertTrue(bad.body().contains("NULL_REQUIRED")); assertTrue(bad.body().contains("UNKNOWN_FIELD"));
        assertEquals(before, CatalogJson.JSON.readTree(call("", null, true).body()).get("drafts"));
    }
    @Test void identityOverridesDuplicateKeysTrailingJsonAndOversizeAreRejected() throws Exception {
        assertEquals(401, call("", null, false).statusCode());
        for (String body : List.of("{\"definition\":" + definition() + ",\"expectedEditVersion\":0,\"tenantId\":\"other\"}",
            "{\"definition\":" + definition() + ",\"expectedEditVersion\":0,\"expectedEditVersion\":0}",
            "{\"definition\":" + definition() + ",\"expectedEditVersion\":0} {}", "x".repeat(65537))) {
            var response = call("/drafts", body, true); assertEquals(400, response.statusCode(), response.body()); assertFalse(response.body().contains("HTTP fixture"));
        }
        assertEquals(400, call("/preview", "{\"definition\":" + definition() + ",\"sample\":{\"name\":{\"nested\":true}}}", true).statusCode());
    }

    @Test void candidateReviewChecksExactSavedContentAndPublicationRechecks() throws Exception {
        String initial=definition().replace("custom.http_test","custom.review_http");
        var saved=CatalogJson.JSON.readTree(call("/drafts","{\"definition\":"+initial+",\"expectedEditVersion\":0}",true).body());
        String command="{\"ref\":{\"id\":\"custom.review_http\",\"revision\":1},\"expectedEditVersion\":1,\"digest\":\""+saved.get("digest").asString()+"\"}";
        var before=call("",null,true).body();var checked=call("/revisions/review",command,true);assertEquals(200,checked.statusCode(),checked.body());assertTrue(CatalogJson.JSON.readTree(checked.body()).get("review").get("compatible").asBoolean());assertEquals(before,call("",null,true).body());
        assertEquals(401,call("/revisions/review",command,false).statusCode());assertEquals(400,call("/revisions/review?tenantId=forged",command,true).statusCode());assertEquals(409,call("/revisions/review",command.replace("expectedEditVersion\":1","expectedEditVersion\":2"),true).statusCode());
        assertEquals(200,call("/publish",command,true).statusCode());assertEquals(409,call("/revisions/review",command,true).statusCode());
        String incompatible=initial.replace("\"revision\":1","\"revision\":2").replace("\"maxLength\":255","\"maxLength\":254");
        var second=CatalogJson.JSON.readTree(call("/drafts","{\"definition\":"+incompatible+",\"expectedEditVersion\":0}",true).body());
        String next=command.replace("\"revision\":1","\"revision\":2").replace(saved.get("digest").asString(),second.get("digest").asString());
        var report=call("/revisions/review",next,true);assertEquals(200,report.statusCode(),report.body());assertFalse(CatalogJson.JSON.readTree(report.body()).get("review").get("compatible").asBoolean());assertTrue(report.body().contains("MAX_LENGTH"));assertEquals(409,call("/publish",next,true).statusCode());assertEquals(404,call("/versions/custom.review_http/2",null,true).statusCode());
    }
    @Test void referencesWithoutWorkflowPermissionAreExplicitAndClosed() throws Exception {
        var value=call("/versions/builtin.host/1/references",null,true);assertEquals(200,value.statusCode(),value.body());var report=CatalogJson.JSON.readTree(value.body()).get("report");assertFalse(report.get("workflowsAvailable").asBoolean());assertEquals("builtin.host",report.get("target").get("id").asString());assertTrue(report.get("references").get("items").size()>0);assertFalse(value.body().contains("authority"));assertFalse(value.body().contains("rawRecords"));assertEquals(400,call("/versions/builtin.host/1/references?tenantId=forged",null,true).statusCode());assertEquals(404,call("/versions/builtin.host/2/references",null,true).statusCode());assertEquals(401,call("/versions/builtin.host/1/references",null,false).statusCode());
    }
}
