package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=pipeline-test-token-not-for-other-use",
    "opsweave.auth.dev.subject=pipeline-user", "opsweave.auth.dev.tenant=tenant-pipeline-http",
    "opsweave.auth.dev.permissions=entity.read,source.sync", "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.zabbix.page-size=1", "opsweave.inventory.store=memory"
})
class PipelineHttpIT {
    private static final String ROOT = "/api/v1/integrations/zabbix/hosts";
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired com.acme.opsweave.platform.persistence.InventoryWiring wiring;

    @Test
    void busySourceReturnsSafeFailureAndAcceptsANewExplicitScanAfterRelease() throws Exception {
        var tenant = new com.acme.opsweave.sharedkernel.TenantId("tenant-pipeline-http");
        var scope = new com.acme.opsweave.inventory.domain.SourceScan.Scope(tenant,"zabbix-1","host");
        var lease = wiring.writer().beginScan(scope,java.util.UUID.randomUUID());
        try {
            var response = call("POST",ROOT+"/sync",null,true);
            assertEquals(503,response.statusCode());
            var failed = json.readTree(response.body());
            assertEquals("SOURCE_SCAN_BUSY",failed.get("failureCode").asString());
            assertEquals(0,failed.get("pages").asInt());assertEquals(0,failed.get("accepted").asInt());
            assertFalse(failed.get("snapshotComplete").asBoolean());
            assertFalse(failed.has("leaseUntil"));assertFalse(failed.has("fence"));assertFalse(failed.has("tenantId"));
            assertEquals(com.acme.opsweave.integration.domain.SyncStatus.FAILED,wiring.syncRuns().find(tenant,java.util.UUID.fromString(failed.get("syncRunId").asString())).orElseThrow().status());
            Path artifacts=Path.of("../../.tmp/source-scan-http");Files.createDirectories(artifacts);
            Files.writeString(artifacts.resolve("host-sync-failure.json"),failed.toString());
        } finally {wiring.writer().releaseScan(lease);}
        assertTrue(ok(call("POST",ROOT+"/sync",null,true)).get("snapshotComplete").asBoolean());
        assertEquals(400,call("POST",ROOT+"/sync",Map.of("fence",1,"leaseUntil","2099-01-01T00:00:00Z"),true).statusCode());
    }

    @Test
    void publishesPreviewsReplaysAndPinsWithoutMutatingInventoryDuringReplay() throws Exception {
        JsonNode sync = ok(call("POST", ROOT + "/sync", null, true));
        JsonNode originalRef = sync.get("pipelineVersion");
        String run = sync.get("syncRunId").asString();
        ObjectNode draft = draft("http-host", 2);
        String before = call("GET", "/api/v1/entities", null, true).body();
        JsonNode preview = ok(call("POST", ROOT + "/pipeline/preview", Map.of("syncRunId", run, "definition", draft), true));
        assertEquals(2, preview.get("accepted").asInt());
        assertTrue(preview.get("changed").asInt() > 0);
        assertEquals(originalRef, preview.get("originalVersion"));
        assertEquals(404, call("POST", ROOT + "/sync", Map.of("pipelineVersion", preview.get("targetVersion")), true).statusCode());
        JsonNode version = ok(call("POST", ROOT + "/pipeline/versions", draft, true));
        assertEquals("PUBLISHED", version.get("state").asString());
        assertEquals(version, ok(call("POST", ROOT + "/pipeline/versions", draft, true)));
        assertEquals(version, ok(call("GET", ROOT + "/pipeline/versions/http-host/2", null, true)));
        ((ObjectNode) draft.get("nodes").get(2).get("config")).put("displayNameField", "name");
        assertEquals(409, call("POST", ROOT + "/pipeline/versions", draft, true).statusCode());
        JsonNode replay = ok(call("POST", ROOT + "/pipeline/replay", Map.of("syncRunId", run,
            "targetVersion", preview.get("targetVersion"), "purpose", "COMPARE_VERSION"), true));
        assertEquals(preview.get("rows"), replay.get("rows"));
        assertTrue(replay.get("dryRun").asBoolean());
        assertFalse(replay.get("writesPerformed").asBoolean());
        assertEquals(before, call("GET", "/api/v1/entities", null, true).body());
        JsonNode second = ok(call("POST", ROOT + "/sync", Map.of("pipelineVersion", preview.get("targetVersion")), true));
        assertEquals(preview.get("targetVersion"), second.get("pipelineVersion"));
        JsonNode defaultAgain = ok(call("POST", ROOT + "/sync", null, true));
        assertEquals(originalRef, defaultAgain.get("pipelineVersion"));
        Path artifacts = Path.of("../../.tmp/pipeline-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("pipeline-version.json"), version.toString());
        Files.writeString(artifacts.resolve("pipeline-evaluation.json"), replay.toString());
    }

    @Test
    void rejectsIdentityOverridesBadGraphsOversizedBodiesAndWriteReplay() throws Exception {
        assertEquals(401, call("POST", ROOT + "/pipeline/versions", draft("unauthorized", 1), false).statusCode());
        ObjectNode draft = draft("http-invalid", 1);
        draft.put("tenantId", "attacker");
        assertEquals(400, call("POST", ROOT + "/pipeline/versions", draft, true).statusCode());
        draft.remove("tenantId");
        ((ObjectNode) draft.get("nodes").get(2).get("config")).put("script", "not allowed");
        assertEquals(400, call("POST", ROOT + "/pipeline/versions", draft, true).statusCode());
        assertEquals(400, call("POST", ROOT + "/pipeline/versions", Map.of("padding", "x".repeat(16385)), true).statusCode());
        assertEquals(400, call("GET", ROOT + "/pipeline/versions/http-host/not-an-integer", null, true).statusCode());
        JsonNode sync = ok(call("POST", ROOT + "/sync", null, true));
        var replay = new LinkedHashMap<String, Object>();
        replay.put("syncRunId", sync.get("syncRunId")); replay.put("targetVersion", sync.get("pipelineVersion"));
        replay.put("purpose", "COMPARE_VERSION"); replay.put("dryRun", false);
        assertEquals(400, call("POST", ROOT + "/pipeline/replay", replay, true).statusCode());
        replay.put("dryRun", true); replay.put("limit", 101);
        assertEquals(400, call("POST", ROOT + "/pipeline/replay", replay, true).statusCode());
        replay.put("limit", 1);
        assertTrue(ok(call("POST", ROOT + "/pipeline/replay", replay, true)).get("truncated").asBoolean());
        replay.put("permissions", "source.sync");
        assertEquals(400, call("POST", ROOT + "/pipeline/replay", replay, true).statusCode());
    }

    @Test
    void durableReplayIsIdempotentAndHasScopedHistory() throws Exception {
        JsonNode sync = ok(call("POST", ROOT + "/sync", null, true));
        var body = new LinkedHashMap<String, Object>();
        body.put("requestKey", java.util.UUID.randomUUID().toString()); body.put("syncRunId", sync.get("syncRunId"));
        body.put("targetVersion", sync.get("pipelineVersion")); body.put("purpose", "COMPARE_VERSION"); body.put("limit", 100);
        String path = ROOT + "/pipeline/replay-runs";
        JsonNode result = ok(call("POST", path, body, true));
        assertEquals("memory", result.get("storage").asString());
        assertEquals("SUCCEEDED", result.get("run").get("state").asString());
        assertEquals(1, result.get("run").get("attempt").asInt());
        assertFalse(result.get("run").get("canResume").asBoolean());
        assertEquals(2, result.get("report").get("accepted").asInt());
        assertEquals(result, ok(call("POST", path, body, true)));
        assertEquals(result, ok(call("GET", path + "/" + result.get("run").get("id").asString(), null, true)));
        JsonNode history = ok(call("GET", path + "?limit=1", null, true));
        assertEquals(1, history.get("items").size());
        assertFalse(history.get("items").get(0).has("report"));
        body.put("limit", 1); assertEquals(409, call("POST", path, body, true).statusCode());
        Path artifacts = Path.of("../../.tmp/pipeline-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("pipeline-replay-run.json"), result.toString());
        Files.writeString(artifacts.resolve("pipeline-replay-history.json"), history.toString());
    }

    @Test
    void durableReplayRejectsOverridesAndExposesOnlySafeFailures() throws Exception {
        String path = ROOT + "/pipeline/replay-runs";
        assertEquals(401, call("GET", path, null, false).statusCode());
        assertEquals(400, call("GET", path + "?limit=51", null, true).statusCode());
        assertEquals(400, call("GET", path + "/1-1-1-1-1", null, true).statusCode());
        JsonNode sync = ok(call("POST", ROOT + "/sync", null, true));
        var body = new LinkedHashMap<String, Object>();
        body.put("requestKey", java.util.UUID.randomUUID().toString()); body.put("syncRunId", java.util.UUID.randomUUID().toString());
        body.put("targetVersion", sync.get("pipelineVersion")); body.put("purpose", "COMPARE_VERSION");
        body.put("owner", "attacker"); assertEquals(400, call("POST", path, body, true).statusCode()); body.remove("owner");
        body.put("dryRun", false); assertEquals(400, call("POST", path, body, true).statusCode()); body.remove("dryRun");
        assertEquals(404, call("POST", path, body, true).statusCode());
        JsonNode history = ok(call("GET", path, null, true));
        JsonNode failed = null;
        for (var item : history.get("items")) if (item.get("requestKey").asString().equals(body.get("requestKey"))) failed = item;
        assertNotNull(failed); assertEquals("FAILED", failed.get("state").asString());
        assertEquals("NOT_FOUND", failed.get("failureCode").asString()); assertTrue(failed.get("canResume").asBoolean());
        assertEquals(404, call("POST", path, body, true).statusCode());
        JsonNode detail = ok(call("GET", path + "/" + failed.get("id").asString(), null, true));
        assertEquals(2, detail.get("run").get("attempt").asInt()); assertTrue(detail.get("report").isNull());
    }

    @Test
    void savesAndLoadsDraftsWithoutPublishingAndRejectsStaleEdits() throws Exception {
        String path = ROOT + "/pipeline/drafts"; ObjectNode definition = draft("http-draft", 3);
        JsonNode first = ok(call("POST", path, Map.of("definition", definition, "expectedEditVersion", 0), true));
        assertEquals("DRAFT", first.get("state").asString()); assertEquals(1, first.get("editVersion").asInt());
        assertEquals(first, ok(call("GET", path + "/http-draft/3", null, true)));
        assertEquals(404, call("GET", ROOT + "/pipeline/versions/http-draft/3", null, true).statusCode());
        ((ObjectNode) definition.get("nodes").get(2).get("config")).put("displayNameField", "name");
        JsonNode second = ok(call("POST", path, Map.of("definition", definition, "expectedEditVersion", 1), true));
        assertEquals(2, second.get("editVersion").asInt()); assertNotEquals(first.get("digest"), second.get("digest"));
        assertEquals(409, call("POST", path, Map.of("definition", definition, "expectedEditVersion", 1), true).statusCode());
        assertEquals(second, ok(call("GET", path + "/http-draft/3", null, true)));
        JsonNode recent = ok(call("GET", path + "?limit=1", null, true)); assertEquals(1, recent.get("items").size());
        assertFalse(recent.get("items").get(0).has("definition"));
        assertEquals(401, call("GET", path, null, false).statusCode());
        assertEquals(400, call("GET", path + "?limit=51", null, true).statusCode());
        assertEquals(400, call("POST", path, Map.of("definition", definition, "expectedEditVersion", -1), true).statusCode());
        assertEquals(400, call("POST", path, Map.of("definition", definition, "expectedEditVersion", 2, "owner", "other"), true).statusCode());
        Path artifacts = Path.of("../../.tmp/pipeline-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("pipeline-draft.json"), second.toString());
        Files.writeString(artifacts.resolve("pipeline-draft-list.json"), recent.toString());
    }

    private ObjectNode draft(String id, int revision) throws Exception {
        var node = (ObjectNode) json.readTree(Files.readString(Path.of("../../contracts/examples/pipeline-definition.json")));
        node.put("id", id); node.put("revision", revision);
        ((ObjectNode) node.get("nodes").get(2)).putObject("config").put("displayNameField", "host");
        return node;
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body());
    }
    private HttpResponse<String> call(String method, String path, Object body, boolean authorized) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (authorized) builder.header("Authorization", "Bearer pipeline-test-token-not-for-other-use");
        if (method.equals("POST")) builder.header("Content-Type", "application/json").POST(body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.GET();
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
