package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.integration.domain.SourceCredential;
import com.acme.opsweave.integration.domain.SourceEndpoint;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Edge;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Node;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Target;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Type;
import com.acme.opsweave.integration.domain.WorkflowOperators;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * OW-ST03/04 closing acceptance: the complete onboarding chain — confirmation receipt, persistent
 * draft, explicit sample preview and immutable publish — is verified step by step against the real
 * HTTP API and real PostgreSQL in loopback dev mode. Every step is an explicit command: reads never
 * execute anything, a failed or rejected preview stops the chain before publish, publishing never
 * starts collection, never writes entities and never calls a model. The same class verifies the
 * bounded cursor pages of workflow entries and run records over real retained rows. Upstream data
 * is an explicitly synthetic Zabbix JSON-RPC fixture (one loopback server); it never claims
 * real-source status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "server.address=127.0.0.1",
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=owst0304-acceptance-fixture-only-32ch",
    "opsweave.auth.dev.subject=owst0304-author",
    "opsweave.auth.dev.permissions=source.sync,source.configure,entity.read,entity.manage,metric.read",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=owst0304-deploy-fixture",
    "opsweave.inventory.store=postgres"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresOwSt0304AcceptanceIT {
    static final String TOKEN = "owst0304-acceptance-fixture-only-32ch";
    static final String SEED = System.getenv("OPSWEAVE_OW_ST0304_SEED") == null ? UUID.randomUUID().toString() : System.getenv("OPSWEAVE_OW_ST0304_SEED");
    static final String TENANT = "ow-st0304-" + SEED;
    static final String SECRET = "owst0304-synthetic-upstream-token";
    static final int PORT = 18311;

    static Path registry, keyring;
    static SyntheticZabbix upstream;

    /** Explicitly synthetic Zabbix JSON-RPC upstream: own bearer secret and own marker rows. */
    static final class SyntheticZabbix {
        final HttpServer server;
        final String secret, version;
        final List<Map<String, Object>> hosts;
        final AtomicInteger reads = new AtomicInteger(), mismatches = new AtomicInteger();

        SyntheticZabbix(int port, String secret, String version, List<Map<String, Object>> hosts) throws IOException {
            this.secret = secret; this.version = version; this.hosts = hosts;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/api_jsonrpc.php", this::handle);
            server.start();
        }

        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            reads.incrementAndGet();
            try {
                var n = CatalogJson.JSON.readTree(exchange.getRequestBody().readAllBytes());
                String method = n.path("method").asText();
                JsonNode params = n.get("params");
                Object result;
                if (method.equals("apiinfo.version")) {
                    result = version;
                } else {
                    if (!("Bearer " + secret).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        mismatches.incrementAndGet();
                        send(exchange, Map.of("error", "wrong bearer secret"));
                        return;
                    }
                    if (method.equals("host.get")) result = hostGet(params);
                    else if (method.equals("item.get")) result = List.of();
                    else { send(exchange, Map.of("error", "unsupported method")); return; }
                }
                send(exchange, Map.of("jsonrpc", "2.0", "id", 1, "result", result));
            } catch (RuntimeException failure) {
                send(exchange, Map.of("error", "synthetic upstream failure"));
            }
        }

        Object hostGet(JsonNode params) {
            if (params.has("countOutput")) return Integer.toString(hosts.size());
            if (params.has("hostids")) {
                var requested = new ArrayList<String>();
                for (var value : params.get("hostids")) requested.add(value.asString());
                var rows = new ArrayList<Map<String, Object>>();
                for (var host : hosts) if (requested.contains(String.valueOf(host.get("hostid")))) rows.add(host);
                return rows;
            }
            var rows = new ArrayList<Map<String, Object>>();
            for (var host : hosts) rows.add(Map.of("hostid", host.get("hostid"), "groups", host.get("groups")));
            return rows;
        }

        void send(com.sun.net.httpserver.HttpExchange exchange, Object payload) throws IOException {
            byte[] body = CatalogJson.JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        }

        void stop() { server.stop(0); }
    }

    static void setup() {
        try {
            if (registry != null) return;
            var host = new LinkedHashMap<String, Object>();
            host.put("hostid", "301");
            host.put("host", "owst0304-host-1");
            host.put("name", "Fixture onboarding host");
            host.put("status", "0");
            host.put("groups", List.of(Map.of("groupid", "3001")));
            host.put("interfaces", List.of(Map.of("ip", "192.0.2.31", "main", "1", "type", "1")));
            upstream = new SyntheticZabbix(PORT, SECRET, "7.0.27", List.of(host));
            registry = Files.writeString(Files.createTempFile("opsweave-owst0304-endpoints-", ".json"), CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion", "2.0", "endpoints", List.of(
                Map.of("id", "owst0304-fixture", "name", "OW-ST0304 synthetic upstream", "connectorKind", "ZABBIX_HOST", "address", "http://127.0.0.1:" + PORT + "/api_jsonrpc.php", "tenants", List.of(TENANT))))));
            keyring = Files.writeString(Files.createTempFile("opsweave-owst0304-keyring-", ".json"), "{\"activeKeyId\":\"owst0304-fixture-key\",\"keys\":{\"owst0304-fixture-key\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}}");
        } catch (Exception failure) {
            throw new IllegalStateException("OW-ST0304 synthetic upstream setup failed", failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        setup();
        r.add("opsweave.auth.dev.tenant", () -> TENANT);
        r.add("opsweave.sources.endpoints-path", () -> registry.toString());
        r.add("opsweave.credentials.keyring-path", () -> keyring.toString());
        r.add("opsweave.inventory.jdbc-url", () -> System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        r.add("opsweave.inventory.jdbc-user", () -> System.getenv("OPSWEAVE_TEST_JDBC_USER"));
        r.add("opsweave.inventory.jdbc-password", () -> System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (upstream != null) upstream.stop();
        if (registry != null) Files.deleteIfExists(registry);
        if (keyring != null) Files.deleteIfExists(keyring);
    }

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    static UUID id(String label) { return UUID.nameUUIDFromBytes((TENANT + "|" + label).getBytes(StandardCharsets.UTF_8)); }

    HttpResponse<String> call(String path, String method, Object body) throws Exception {
        return call(path, method, body, true);
    }

    HttpResponse<String> call(String path, String method, Object body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (authenticated) request.header("Authorization", "Bearer " + TOKEN);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String path, String method, Object body) throws Exception {
        var response = call(path, method, body);
        assertEquals(200, response.statusCode(), method + " " + path + ": " + response.body());
        return CatalogJson.JSON.readTree(response.body());
    }

    // ---------- shared deterministic commands ----------

    JsonNode sourcePage() throws Exception { return ok("/api/v1/integrations/sources", "GET", null); }

    JsonNode model(JsonNode page) {
        for (var candidate : page.get("models")) if (candidate.get("definition").get("kind").asString().equals("ENTITY") && candidate.get("definition").get("fields").size() > 0) return candidate;
        throw new IllegalStateException("the authorized catalog exposes an entity model with fields");
    }

    String connectionDigest(JsonNode page, String kind) {
        for (var type : page.get("types")) {
            if (type.get("id").asString().equals(kind) && type.get("connection") != null && !type.get("connection").isNull()) return type.get("connection").get("digest").asString();
        }
        throw new IllegalStateException("source type " + kind);
    }

    /** A sample record that satisfies every mapped model field of a seeded manual draft. */
    Map<String, Object> sample(JsonNode model) {
        var sample = new LinkedHashMap<String, Object>();
        for (var field : model.get("definition").get("fields")) sample.put(field.get("id").asString(), value(field));
        assertFalse(sample.isEmpty(), "the seeded mapping covers at least one field");
        return sample;
    }

    static Object value(JsonNode field) {
        String type = field.get("type").asString();
        if (type.equals("INTEGER") && field.has("min")) return field.get("min").intValue();
        if (type.equals("DECIMAL") && field.has("min")) return field.get("min").decimalValue();
        return switch (type) {
            case "TEXT" -> "fixture-value";
            case "INTEGER" -> 3;
            case "DECIMAL" -> 1.5;
            case "BOOLEAN" -> true;
            case "ENUM" -> field.get("choices").get(0).asString();
            case "DATETIME" -> "2026-10-13T08:00:00Z";
            default -> "fixture-value";
        };
    }

    Map<String, Object> confirmBody(String label, JsonNode page) {
        var model = model(page);
        return new LinkedHashMap<String, Object>(Map.of(
            "requestId", id(label).toString(),
            "name", "OW-ST0304 onboarding " + label,
            "description", "synthetic fixture only",
            "source", Map.of("kind", "MANUAL_SAMPLE", "instanceId", "manual"),
            "connectionDigest", connectionDigest(page, "MANUAL_SAMPLE"),
            "target", Map.of("id", model.get("definition").get("id").asString(), "revision", 1, "digest", model.get("digest").asString())));
    }

    // ---------- registered connection helpers (chain B) ----------

    SourceEndpoint.Pin endpointPin(String endpointId) throws Exception {
        var endpoint = ok("/api/v2/data-sources/endpoints/" + endpointId, "GET", null).get("endpoint");
        return new SourceEndpoint.Pin(endpoint.get("id").asString(), endpoint.get("digest").asString());
    }

    JsonNode registerInstance(String label) throws Exception {
        var pin = endpointPin("owst0304-fixture");
        ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id(label + "-credential"), "name", "OW-ST0304 synthetic credential " + label, "secret", SECRET));
        var credential = new SourceCredential.Pin(id(label + "-credential"), 1, id(label + "-credential"));
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", id(label + "-instance"));
        body.put("name", "OW-ST0304 synthetic instance " + label);
        body.put("description", "OW-ST0304 synthetic registered connection");
        body.put("endpointPin", Map.of("id", pin.id(), "digest", pin.digest()));
        body.put("credentialPin", Map.of("credentialId", credential.credentialId().toString(), "revision", credential.revision(), "versionId", credential.versionId().toString()));
        body.put("hostGroupIds", List.of("3001"));
        var created = ok("/api/v2/data-sources/connections", "POST", body);
        return created;
    }

    String connectionDigest(JsonNode created) { return created.get("connection").get("connectionDigest").asString(); }

    WorkflowDefinition.Source pinnedSource(UUID instanceId, String digest) {
        return new WorkflowDefinition.Source("ZABBIX_HOST", "connection-" + instanceId, new WorkflowDefinition.ConfigurationPin(instanceId, 1, digest));
    }

    WorkflowDefinition graph(String workflowId, WorkflowDefinition.Source source, JsonNode model, String name) {
        var fields = new LinkedHashMap<String, String>();
        if (source.kind().equals("MANUAL_SAMPLE")) {
            for (var field : model.get("definition").get("fields")) fields.put(field.get("id").asString(), field.get("id").asString());
        } else {
            // Registered Zabbix host reads expose the fixed record keys name/ip/lifecycle/entity_id.
            for (var field : model.get("definition").get("fields")) {
                if (field.get("id").asString().equals("hostname") || field.get("id").asString().equals("name")) fields.put("name", field.get("id").asString());
                if (field.get("id").asString().equals("ip")) fields.put("ip", "ip");
            }
            if (fields.isEmpty()) fields.put("name", model.get("definition").get("fields").get(0).get("id").asString());
        }
        var nodes = List.of(new Node("source", Type.SOURCE, "1", Map.of()), new Node("mapping", Type.MAP, "1", fields),
            new Node("trim", Type.TRIM, "1", Map.of()), new Node("validate", Type.VALIDATE, "1", Map.of()), new Node("output", Type.OUTPUT, "1", Map.of()));
        var edges = List.of(new Edge("source", "mapping"), new Edge("mapping", "trim"), new Edge("trim", "validate"), new Edge("validate", "output"));
        var modelRef = new Target(model.get("definition").get("id").asString(), 1, model.get("digest").asString());
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition(workflowId, 1, name, source, modelRef, nodes, edges));
    }

    HttpResponse<String> saveDraft(String workflowId, WorkflowDefinition.Source source, JsonNode model, int expectedEditVersion) throws Exception {
        return saveDraft(workflowId, source, model, expectedEditVersion, "OW-ST0304 pinned draft");
    }

    HttpResponse<String> saveDraft(String workflowId, WorkflowDefinition.Source source, JsonNode model, int expectedEditVersion, String name) throws Exception {
        var definition = graph(workflowId, source, model, name);
        var layout = new LinkedHashMap<String, Object>();
        for (int i = 0; i < definition.nodes().size(); i++) layout.put(definition.nodes().get(i).id(), Map.of("x", 100, "y", i * 100));
        return call("/api/v1/integrations/workflows/drafts", "POST", Map.of("definition", WorkflowJson.wire(definition), "layout", layout, "expectedEditVersion", expectedEditVersion));
    }

    Map<String, Object> previewBody(String id, int editVersion, String digest, List<Map<String, Object>> samples) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id);
        body.put("revision", 1);
        body.put("editVersion", editVersion);
        body.put("digest", digest);
        body.put("dryRun", true);
        if (samples != null) body.put("samples", samples);
        return body;
    }

    void assertZeroSideEffects() throws Exception {
        var entities = ok("/api/v1/entities/page?limit=50", "GET", null).get("items");
        assertEquals(0, entities.size(), "onboarding and preview never write entity instances");
        var runtime = ok("/api/v1/integrations/workflows/runtime", "GET", null);
        assertEquals(0, runtime.get("tasks").size(), "onboarding never starts a collection task");
        assertEquals(0, runtime.get("executions").size(), "onboarding never starts a workflow execution");
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))) {
            for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
                try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
                    statement.setString(1, TENANT);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1), table + " must stay empty: the chain never calls AI or models");
                    }
                }
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    // ---------- a. confirmation -> persistent draft -> explicit preview -> immutable publish ----------

    @Test
    @Order(1)
    void a_onboardingConfirmationEntersDraftPreviewAndPublish() throws Exception {
        var page = sourcePage();
        var model = model(page);
        Map<String, Object> command = confirmBody("chain-a", page);
        String requestId = id("chain-a").toString(), workflowId = "source-" + requestId;

        var confirmed = ok("/api/v1/integrations/sources/confirm", "POST", command);
        assertEquals(requestId, confirmed.get("setup").get("id").asString());
        var workflow = confirmed.get("workflow");
        assertEquals("DRAFT", workflow.get("state").asString());
        assertTrue(workflow.get("preview").isNull(), "confirmation never previews or executes");
        assertEquals(workflowId, workflow.get("definition").get("id").asString());
        assertEquals(5, workflow.get("definition").get("nodes").size());
        String digest = workflow.get("digest").asString();

        // Reads of the receipt, the draft and the continuation are explicit and read-only.
        assertEquals(0, ok("/api/v1/integrations/workflows", "GET", null).get("runs").get("items").size());
        var draft = ok("/api/v1/integrations/workflows/drafts/" + workflowId + "/1", "GET", null);
        assertTrue(draft.get("preview").isNull());
        assertEquals(digest, draft.get("digest").asString());
        var continuation = ok("/api/v1/integrations/sources/" + requestId + "/continuation", "GET", null);
        assertEquals(workflowId, continuation.get("workflow").get("definition").get("id").asString());
        assertEquals(0, ok("/api/v1/integrations/workflows", "GET", null).get("runs").get("items").size(), "reads never execute the workflow");

        // Publish before any preview is an explicit rejection and creates no version.
        var premature = call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", digest, "previewId", id("no-preview")));
        assertEquals(409, premature.statusCode(), premature.body());
        assertEquals("PREVIEW_REQUIRED", CatalogJson.JSON.readTree(premature.body()).get("error").asString());
        assertEquals(404, call("/api/v1/integrations/workflows/versions/" + workflowId + "/1", "GET", null).statusCode());

        // Explicit sample preview: dry-run, no writes, and it records exactly one run receipt.
        var sample = sample(model);
        var preview = call("/api/v1/integrations/workflows/preview", "POST", previewBody(workflowId, 1, digest, List.of(sample, sample)));
        assertEquals(200, preview.statusCode(), preview.body());
        var result = CatalogJson.JSON.readTree(preview.body());
        assertEquals(2, result.get("evaluation").get("accepted").asInt());
        assertEquals(0, result.get("evaluation").get("rejected").asInt());
        assertTrue(result.get("evaluation").get("dryRun").asBoolean());
        assertFalse(result.get("evaluation").get("writesPerformed").asBoolean());
        assertEquals("MANUAL_SAMPLE", result.get("receipt").get("origin").asString());
        String previewId = result.get("receipt").get("id").asString();
        assertEquals(1, ok("/api/v1/integrations/workflows", "GET", null).get("runs").get("items").size());

        // Publish closes the chain into an immutable version; replaying the command is idempotent.
        var published = ok("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", digest, "previewId", previewId));
        assertEquals("PUBLISHED", published.get("state").asString());
        assertEquals(0, published.get("editVersion").asInt());
        assertEquals(digest, published.get("digest").asString());
        var version = ok("/api/v1/integrations/workflows/versions/" + workflowId + "/1", "GET", null);
        assertEquals(published, version);
        assertEquals(published, ok("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", digest, "previewId", previewId)));
        var workspace = ok("/api/v1/integrations/workflows", "GET", null);
        assertEquals(0, workspace.get("drafts").get("items").size(), "a published revision leaves the active draft list");
        assertEquals(1, workspace.get("published").get("items").size());
        assertZeroSideEffects();
    }

    // ---------- b. registered-source chain reads its own source before publishing ----------

    @Test
    @Order(2)
    void b_registeredSourceConfirmationDraftPreviewAndPublish() throws Exception {
        var created = registerInstance("chain-b");
        UUID instanceId = UUID.fromString(created.get("receipt").get("sourceId").asString());
        String digest = connectionDigest(created);
        var page = sourcePage();
        var model = model(page);
        String workflowId = "source-" + instanceId;

        var saved = saveDraft(workflowId, pinnedSource(instanceId, digest), model, 0);
        assertEquals(200, saved.statusCode(), saved.body());
        var entry = CatalogJson.JSON.readTree(saved.body());
        assertEquals(1, entry.get("editVersion").asInt());
        var pin = entry.get("definition").get("source").get("configuration");
        assertEquals(instanceId.toString(), pin.get("sourceId").asString());
        assertEquals(digest, pin.get("digest").asString(), "the draft pins the confirmed configuration");

        // The instant sample read is explicit and contacts this instance's own upstream only.
        int readsBefore = upstream.reads.get();
        var preview = call("/api/v1/integrations/workflows/preview", "POST", previewBody(workflowId, 1, entry.get("digest").asString(), null));
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(upstream.reads.get() > readsBefore, "the explicit preview performed a bounded source read");
        assertEquals(0, upstream.mismatches.get(), "the read uses only this instance's credential");
        var result = CatalogJson.JSON.readTree(preview.body());
        assertEquals(1, result.get("evaluation").get("accepted").asInt(), result.toString());
        assertEquals(0, result.get("evaluation").get("rejected").asInt());
        assertEquals("zabbix-jsonrpc", result.get("receipt").get("origin").asString());
        assertEquals("SUCCEEDED", result.get("sourceStatus").asString());
        assertEquals(0, result.get("missingRaw").asInt());
        assertTrue(result.get("evaluation").get("dryRun").asBoolean());
        assertFalse(result.get("evaluation").get("writesPerformed").asBoolean());
        String previewId = result.get("receipt").get("id").asString();

        int readsAfterPreview = upstream.reads.get();
        ok("/api/v1/integrations/workflows/drafts/" + workflowId + "/1", "GET", null);
        ok("/api/v1/integrations/workflows", "GET", null);
        assertEquals(readsAfterPreview, upstream.reads.get(), "reads never touch the upstream source");

        var published = ok("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", entry.get("digest").asString(), "previewId", previewId));
        assertEquals("PUBLISHED", published.get("state").asString());
        assertEquals(instanceId.toString(), published.get("definition").get("source").get("configuration").get("sourceId").asString(),
            "the published version stays pinned to the confirmed configuration");
        assertZeroSideEffects();
    }

    // ---------- c. failed or stale previews stop the chain before publish ----------

    @Test
    @Order(3)
    void c_failedOrStalePreviewsStopTheChainBeforePublish() throws Exception {
        var page = sourcePage();
        var model = model(page);
        Map<String, Object> command = confirmBody("chain-c", page);
        String requestId = id("chain-c").toString(), workflowId = "source-" + requestId;
        var confirmed = ok("/api/v1/integrations/sources/confirm", "POST", command);
        String digest = confirmed.get("workflow").get("digest").asString();

        // A rejected row (a required mapped field is missing) cannot publish.
        var rejected = CatalogJson.JSON.readTree(call("/api/v1/integrations/workflows/preview", "POST", previewBody(workflowId, 1, digest, List.of(Map.of()))).body());
        assertEquals(0, rejected.get("evaluation").get("accepted").asInt());
        assertEquals(1, rejected.get("evaluation").get("rejected").asInt());
        var blocked = call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", digest, "previewId", rejected.get("receipt").get("id").asString()));
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals(404, call("/api/v1/integrations/workflows/versions/" + workflowId + "/1", "GET", null).statusCode());

        // A good preview is invalidated by a semantic edit; the stale receipt cannot publish.
        var sample = sample(model);
        var good = CatalogJson.JSON.readTree(call("/api/v1/integrations/workflows/preview", "POST", previewBody(workflowId, 1, digest, List.of(sample))).body());
        assertEquals(1, good.get("evaluation").get("accepted").asInt());
        var edited = saveDraft(workflowId, new WorkflowDefinition.Source("MANUAL_SAMPLE", "manual"), model, 1, "OW-ST0304 renamed before publish");
        assertEquals(200, edited.statusCode(), edited.body());
        var saved = CatalogJson.JSON.readTree(edited.body());
        assertEquals(2, saved.get("editVersion").asInt());
        assertTrue(saved.get("preview").isNull(), "a semantic change clears the old preview");
        var stale = call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 2, "digest", saved.get("digest").asString(), "previewId", good.get("receipt").get("id").asString()));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(404, call("/api/v1/integrations/workflows/versions/" + workflowId + "/1", "GET", null).statusCode());

        // Wrong digest or wrong edit version never publishes either.
        assertEquals(409, call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 2, "digest", "sha256:" + "0".repeat(64), "previewId", good.get("receipt").get("id").asString())).statusCode());
        assertEquals(409, call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", workflowId, "revision", 1, "editVersion", 1, "digest", saved.get("digest").asString(), "previewId", good.get("receipt").get("id").asString())).statusCode());
        assertEquals(404, call("/api/v1/integrations/workflows/versions/" + workflowId + "/1", "GET", null).statusCode());

        // An unreachable registered source fails the preview explicitly and leaves no run receipt.
        var created = registerInstance("chain-c-registered");
        UUID instanceId = UUID.fromString(created.get("receipt").get("sourceId").asString());
        String instanceDigest = connectionDigest(created);
        String pinnedId = "source-" + instanceId;
        var pinnedDraft = CatalogJson.JSON.readTree(saveDraft(pinnedId, pinnedSource(instanceId, instanceDigest), model, 0).body());
        String pinnedDefinitionDigest = pinnedDraft.get("digest").asString();
        upstream.stop();
        int runsBefore = ok("/api/v1/integrations/workflows", "GET", null).get("runs").get("items").size();
        var failed = call("/api/v1/integrations/workflows/preview", "POST", previewBody(pinnedId, 1, pinnedDefinitionDigest, null));
        assertTrue(failed.statusCode() == 400 || failed.statusCode() == 503, "an unreadable source is an explicit failure: " + failed.statusCode() + " " + failed.body());
        assertEquals(runsBefore, ok("/api/v1/integrations/workflows", "GET", null).get("runs").get("items").size(), "a failed preview leaves no run receipt");
        assertEquals(409, call("/api/v1/integrations/workflows/publish", "POST", Map.of("id", pinnedId, "revision", 1, "editVersion", 1, "digest", pinnedDefinitionDigest, "previewId", id("failed-preview"))).statusCode());
        assertEquals(404, call("/api/v1/integrations/workflows/versions/" + pinnedId + "/1", "GET", null).statusCode());
        assertZeroSideEffects();
    }

    // ---------- d. bounded cursor pages cover the retained entries and runs ----------

    @Test
    @Order(4)
    void d_entryAndRunPagesCoverTheRetainedHistoryWithExplicitCursors() throws Exception {
        var page = sourcePage();
        var model = model(page);
        var created = new ArrayList<String>();
        for (int i = 1; i <= 23; i++) {
            String workflowId = "page-flow-" + i;
            var saved = saveDraft(workflowId, new WorkflowDefinition.Source("MANUAL_SAMPLE", "manual"), model, 0);
            assertEquals(200, saved.statusCode(), saved.body());
            created.add(workflowId);
        }

        // Entry cursor pages cover every retained draft exactly once in one deterministic order.
        var seen = new java.util.LinkedHashSet<String>();
        var walked = new ArrayList<JsonNode>();
        String cursor = null;
        boolean hasMore = true;
        while (hasMore) {
            var response = ok("/api/v1/integrations/workflows/entries?state=DRAFT&limit=10" + (cursor == null ? "" : "&after=" + cursor), "GET", null);
            assertEquals("2.0", response.get("schemaVersion").asString());
            hasMore = response.get("hasMore").asBoolean();
            var items = response.get("items");
            assertTrue(items.size() > 0 && items.size() <= 10);
            if (hasMore) {
                var last = items.get(items.size() - 1);
                assertEquals(last.get("definition").get("id").asString() + ":" + last.get("definition").get("revision").asInt(), response.get("nextCursor").asString());
            } else {
                assertTrue(response.get("nextCursor").isNull(), "an empty tail discloses the end of the retained set");
            }
            for (var item : items) {
                assertTrue(seen.add(item.get("definition").get("id").asString()), "cursor pages never repeat an entry");
                walked.add(item);
            }
            cursor = hasMore ? response.get("nextCursor").asString() : null;
        }
        assertTrue(walked.size() > 20, "pagination reaches beyond the 20-item directory window: " + walked.size());
        assertTrue(seen.containsAll(created), "every retained draft is reachable through the pages");
        for (int i = 1; i < walked.size(); i++) {
            assertTrue(walked.get(i - 1).get("updatedAt").asString().compareTo(walked.get(i).get("updatedAt").asString()) >= 0, "entries stay ordered by update time");
        }
        var directory = ok("/api/v1/integrations/workflows", "GET", null);
        assertTrue(directory.get("drafts").get("truncated").asBoolean(), "the directory first page discloses its own truncation");
        assertTrue(directory.get("drafts").get("items").size() < walked.size());

        // A per-workflow page and the merged page keep the same closed semantics.
        var single = ok("/api/v1/integrations/workflows/entries?workflowId=page-flow-5&limit=10", "GET", null);
        assertEquals(1, single.get("items").size());
        assertFalse(single.get("hasMore").asBoolean());
        var publishedWalk = ok("/api/v1/integrations/workflows/entries?state=PUBLISHED&limit=50", "GET", null);
        var publishedIds = new java.util.LinkedHashSet<String>();
        for (var item : publishedWalk.get("items")) publishedIds.add(item.get("definition").get("id").asString());
        assertTrue(publishedIds.contains("source-" + id("chain-a")), "the published onboarding version is readable through the page");
        assertFalse(publishedWalk.get("hasMore").asBoolean());

        // Twelve explicit previews produce twelve run receipts, reachable through run cursor pages.
        var receipts = new ArrayList<String>();
        for (int i = 1; i <= 12; i++) {
            String workflowId = "page-flow-" + i;
            var draft = ok("/api/v1/integrations/workflows/drafts/" + workflowId + "/1", "GET", null);
            var result = CatalogJson.JSON.readTree(call("/api/v1/integrations/workflows/preview", "POST", previewBody(workflowId, 1, draft.get("digest").asString(), List.of(sample(model)))).body());
            assertEquals(1, result.get("evaluation").get("accepted").asInt());
            receipts.add(result.get("receipt").get("id").asString());
        }
        var runSeen = new java.util.LinkedHashSet<String>();
        cursor = null;
        hasMore = true;
        while (hasMore) {
            var response = ok("/api/v1/integrations/workflows/runs?limit=5" + (cursor == null ? "" : "&after=" + cursor), "GET", null);
            assertEquals("2.0", response.get("schemaVersion").asString());
            hasMore = response.get("hasMore").asBoolean();
            var items = response.get("items");
            assertTrue(items.size() > 0 && items.size() <= 5);
            if (hasMore) {
                assertEquals(items.get(items.size() - 1).get("receipt").get("id").asString(), response.get("nextCursor").asString(), "the run cursor is the last returned receipt");
            } else {
                assertTrue(response.get("nextCursor").isNull());
            }
            for (var item : items) assertTrue(runSeen.add(item.get("receipt").get("id").asString()), "cursor pages never repeat a run");
            cursor = hasMore ? response.get("nextCursor").asString() : null;
        }
        assertTrue(runSeen.containsAll(receipts), "every recorded run is reachable through the pages");
        var runsDirectory = ok("/api/v1/integrations/workflows", "GET", null).get("runs");
        assertFalse(runsDirectory.get("truncated").asBoolean());
        var directoryRuns = new java.util.LinkedHashSet<String>();
        for (var item : runsDirectory.get("items")) directoryRuns.add(item.get("receipt").get("id").asString());
        assertEquals(directoryRuns, runSeen, "the pages cover exactly the retained run set");
        assertEquals("PREVIEW", ok("/api/v1/integrations/workflows/runs/" + receipts.get(0), "GET", null).get("run").get("mode").asString());

        // Closed query contract: identity, page size and cursors can never be forged.
        assertEquals(401, call("/api/v1/integrations/workflows/entries", "GET", null, false).statusCode());
        assertEquals(401, call("/api/v1/integrations/workflows/runs", "GET", null, false).statusCode());
        for (String query : List.of("?limit=0", "?limit=51", "?limit=x", "?state=RUNNING", "?workflowId=Bad", "?after=BAD:1", "?after=page-flow", "?after=page-flow:0", "?tenantId=other", "?owner=me")) {
            assertEquals(400, call("/api/v1/integrations/workflows/entries" + query, "GET", null).statusCode(), query);
        }
        assertEquals(400, call("/api/v1/integrations/workflows/entries?after=page-flow-1:1&after=page-flow-2:1", "GET", null).statusCode());
        assertEquals(400, call("/api/v1/integrations/workflows/runs?limit=51", "GET", null).statusCode());
        assertEquals(400, call("/api/v1/integrations/workflows/runs?after=not-a-uuid", "GET", null).statusCode());
        assertEquals(400, call("/api/v1/integrations/workflows/runs?tenantId=other", "GET", null).statusCode());
        assertEquals(404, call("/api/v1/integrations/workflows/entries?after=absent-flow:1", "GET", null).statusCode());
        assertEquals(404, call("/api/v1/integrations/workflows/runs?after=" + UUID.randomUUID(), "GET", null).statusCode());

        // Pagination is read-only: no run, no version and no side effect appears from the reads.
        var afterReads = ok("/api/v1/integrations/workflows", "GET", null);
        assertEquals(runsDirectory.get("items").size(), afterReads.get("runs").get("items").size());
        assertZeroSideEffects();
    }
}
