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
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
 * OW-ST02 closing acceptance against the real HTTP API and real PostgreSQL in loopback dev mode.
 * Two same-vendor registered instances keep separate identities (endpoint, credential pin, host
 * group scope), receipts/discovery/scan records stay with their own instance, one instance's outage
 * or credential revocation never crosses to the other, connection receipts invalidate when the
 * configuration changes, failed/duplicate confirmations leave no duplicate or half instance, and
 * each confirmation enters its own persistent workflow draft without starting collection or
 * calling models. Upstreams are explicitly synthetic Zabbix JSON-RPC fixtures (two loopback
 * servers with independent bearer secrets and marker rows); they never claim real-source status.
 * The {@code d_*} method is strictly read-only (GET and SELECT only) so a second JVM run of just
 * {@code d_*} proves the rows survive a platform process restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "server.address=127.0.0.1",
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=owst02-acceptance-fixture-only-32chars",
    "opsweave.auth.dev.subject=owst02-author",
    "opsweave.auth.dev.permissions=source.sync,source.configure,entity.read,entity.manage,metric.read",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=owst02-deploy-fixture",
    "opsweave.inventory.store=postgres"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresOwSt02AcceptanceIT {
    static final String TOKEN = "owst02-acceptance-fixture-only-32chars";
    static final String SEED = System.getenv("OPSWEAVE_OW_ST02_SEED") == null ? UUID.randomUUID().toString() : System.getenv("OPSWEAVE_OW_ST02_SEED");
    static final String TENANT = "ow-st02-" + SEED;
    static final String SECRET_A = "owst02-synthetic-upstream-a-token", SECRET_B = "owst02-synthetic-upstream-b-token";
    static final int PORT_A = 18301, PORT_B = 18302;

    static Path registry, keyring;
    static SyntheticZabbix upstreamA, upstreamB;

    /** Explicitly synthetic Zabbix JSON-RPC upstream: own bearer secret and own marker rows. */
    static final class SyntheticZabbix {
        final HttpServer server;
        final String secret, version;
        final List<Map<String, Object>> hosts, items;
        final AtomicInteger reads = new AtomicInteger(), mismatches = new AtomicInteger(), unknown = new AtomicInteger(), failures = new AtomicInteger();

        SyntheticZabbix(int port, String secret, String version, List<Map<String, Object>> hosts, List<Map<String, Object>> items) throws IOException {
            this.secret = secret; this.version = version; this.hosts = hosts; this.items = items;
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
                    if (exchange.getRequestHeaders().getFirst("Authorization") != null) { mismatches.incrementAndGet(); send(exchange, Map.of("error", "unexpected authorization")); return; }
                    result = version;
                } else {
                    if (!("Bearer " + secret).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { mismatches.incrementAndGet(); send(exchange, Map.of("error", "wrong bearer secret")); return; }
                    if (method.equals("host.get")) result = hostGet(params);
                    else if (method.equals("item.get")) result = itemGet(params);
                    else { unknown.incrementAndGet(); send(exchange, Map.of("error", "unsupported method")); return; }
                }
                send(exchange, Map.of("jsonrpc", "2.0", "id", 1, "result", result));
            } catch (RuntimeException failure) {
                failures.incrementAndGet();
                send(exchange, Map.of("error", "synthetic upstream failure"));
            }
        }

        Object hostGet(JsonNode params) {
            if (params.has("countOutput")) return Integer.toString(hosts.size());
            var rows = new ArrayList<Map<String, Object>>();
            if (params.has("hostids")) {
                var requested = new ArrayList<String>();
                for (var value : params.get("hostids")) requested.add(value.asString());
                for (var host : hosts) if (requested.contains(String.valueOf(host.get("hostid")))) rows.add(host);
            } else {
                for (var host : hosts) rows.add(Map.of("hostid", host.get("hostid"), "groups", host.get("groups")));
            }
            return rows;
        }

        Object itemGet(JsonNode params) {
            if (params.has("countOutput")) return Integer.toString(items.size());
            var rows = new ArrayList<Map<String, Object>>();
            if (params.has("itemids")) {
                var requested = new ArrayList<String>();
                for (var value : params.get("itemids")) requested.add(value.asString());
                for (var item : items) if (requested.contains(String.valueOf(item.get("itemid")))) rows.add(item);
            } else if (params.get("output").size() == 2) {
                for (var item : items) rows.add(Map.of("itemid", item.get("itemid"), "hostid", item.get("hostid")));
            } else {
                rows.addAll(items);
            }
            return rows;
        }

        void send(com.sun.net.httpserver.HttpExchange exchange, Object payload) throws IOException {
            byte[] body = CatalogJson.JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        }

        void stop() { server.stop(0); }
    }

    static Map<String, Object> host(String hostid, String host, String name, Object status, String group, String... ips) {
        var row = new LinkedHashMap<String, Object>();
        row.put("hostid", hostid); row.put("host", host);
        if (name != null) row.put("name", name);
        row.put("status", status);
        row.put("groups", List.of(Map.of("groupid", group)));
        var interfaces = new ArrayList<Map<String, Object>>();
        for (String ip : ips) interfaces.add(Map.of("ip", ip, "main", "1", "type", "1"));
        row.put("interfaces", interfaces);
        return row;
    }

    static Map<String, Object> item(String itemid, String hostid, String key, String name, String units, String valueType) {
        return Map.of("itemid", itemid, "hostid", hostid, "key_", key, "name", name, "units", units, "value_type", valueType);
    }

    static void setup() {
        try {
            if (registry != null) return;
            upstreamA = new SyntheticZabbix(PORT_A, SECRET_A, "7.0.0",
                List.of(host("101", "owst02-a-host-1", "Fixture A host one", 0, "1001", "192.0.2.11"),
                        host("102", "owst02-a-host-2", "Fixture A host two", 1, "1001", "192.0.2.12")),
                List.of(item("21001", "101", "system.cpu.util[,user]", "Fixture A CPU", "%", "0"),
                        item("21002", "101", "fixture.owst02.a.unmapped", "Fixture A unmapped", "", "5")));
            upstreamB = new SyntheticZabbix(PORT_B, SECRET_B, "7.0.27",
                List.of(host("201", "owst02-b-host-1", null, "0", "2001")),
                List.of(item("22001", "201", "vm.memory.size[pavailable]", "Fixture B memory", "%", "0")));
            registry = Files.writeString(Files.createTempFile("opsweave-owst02-endpoints-", ".json"), CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion", "2.0", "endpoints", List.of(
                Map.of("id", "owst02-fixture-a", "name", "OW-ST02 synthetic upstream A", "connectorKind", "ZABBIX_HOST", "address", "http://127.0.0.1:" + PORT_A + "/api_jsonrpc.php", "tenants", List.of(TENANT)),
                Map.of("id", "owst02-fixture-b", "name", "OW-ST02 synthetic upstream B", "connectorKind", "ZABBIX_HOST", "address", "http://127.0.0.1:" + PORT_B + "/api_jsonrpc.php", "tenants", List.of(TENANT))))));
            keyring = Files.writeString(Files.createTempFile("opsweave-owst02-keyring-", ".json"), "{\"activeKeyId\":\"owst02-fixture-key\",\"keys\":{\"owst02-fixture-key\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}}");
        } catch (Exception failure) {
            throw new IllegalStateException("OW-ST02 synthetic upstream setup failed", failure);
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
        if (upstreamA != null) upstreamA.stop();
        if (upstreamB != null) upstreamB.stop();
        if (registry != null) Files.deleteIfExists(registry);
        if (keyring != null) Files.deleteIfExists(keyring);
    }

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    static UUID id(String label) { return UUID.nameUUIDFromBytes((TENANT + "|" + label).getBytes(StandardCharsets.UTF_8)); }

    HttpResponse<String> call(String path, String method, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        request.header("Authorization", "Bearer " + TOKEN);
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

    SourceEndpoint.Pin endpointPin(String endpointId) throws Exception {
        var endpoint = ok("/api/v2/data-sources/endpoints/" + endpointId, "GET", null).get("endpoint");
        return new SourceEndpoint.Pin(endpoint.get("id").asString(), endpoint.get("digest").asString());
    }

    static SourceCredential.Pin credentialPin(String label) { return new SourceCredential.Pin(id(label), 1, id(label)); }

    Map<String, Object> connectionBody(String label, String name, SourceEndpoint.Pin endpointPin, SourceCredential.Pin credentialPin, List<String> groups) {
        return connectionBody(label, name, endpointPin, credentialPin, groups, null);
    }

    Map<String, Object> connectionBody(String label, String name, SourceEndpoint.Pin endpointPin, SourceCredential.Pin credentialPin, List<String> groups, Integer expectedEditVersion) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", id(label).toString());
        if (expectedEditVersion != null) body.put("expectedEditVersion", expectedEditVersion);
        body.put("name", name);
        body.put("description", "OW-ST02 synthetic registered connection");
        body.put("endpointPin", Map.of("id", endpointPin.id(), "digest", endpointPin.digest()));
        body.put("credentialPin", Map.of("credentialId", credentialPin.credentialId().toString(), "revision", credentialPin.revision(), "versionId", credentialPin.versionId().toString()));
        body.put("hostGroupIds", groups);
        return body;
    }

    static SourceCredential.Pin credentialPin(JsonNode connection) {
        var pin = connection.get("credentialPin");
        return new SourceCredential.Pin(UUID.fromString(pin.get("credentialId").asString()), pin.get("revision").asInt(), UUID.fromString(pin.get("versionId").asString()));
    }

    JsonNode inspection(UUID instanceId, String path, UUID requestId, int revision, String digest) throws Exception {
        return ok("/api/v2/data-sources/" + instanceId + "/" + path, "POST", Map.of("requestId", requestId, "configurationRevision", revision, "connectionDigest", digest)).get("view");
    }

    JsonNode connectionView(UUID instanceId) throws Exception {
        return ok("/api/v2/data-sources/" + instanceId + "/connection", "GET", null);
    }

    WorkflowDefinition draftGraph(String workflowId, WorkflowDefinition.Source source) {
        var model = new BuiltinCatalog().definitions().stream().filter(m -> m.id().equals("builtin.host")).findFirst().orElseThrow();
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition(workflowId, 1, "OW-ST02 instance draft", source, new Target(model.id(), 1, model.digest()),
            List.of(new Node("source", Type.SOURCE, "1", Map.of()), new Node("map", Type.MAP, "1", Map.of("name", "hostname", "ip", "ip")),
                new Node("validate", Type.VALIDATE, "1", Map.of()), new Node("output", Type.OUTPUT, "1", Map.of())),
            List.of(new Edge("source", "map"), new Edge("map", "validate"), new Edge("validate", "output"))));
    }

    HttpResponse<String> saveDraft(String workflowId, WorkflowDefinition.Source source, int expectedEditVersion) throws Exception {
        var definition = draftGraph(workflowId, source);
        var layout = new LinkedHashMap<String, Object>();
        for (int i = 0; i < definition.nodes().size(); i++) layout.put(definition.nodes().get(i).id(), Map.of("x", 100, "y", i * 100));
        return call("/api/v1/integrations/workflows/drafts", "POST", Map.of("definition", WorkflowJson.wire(definition), "layout", layout, "expectedEditVersion", expectedEditVersion));
    }

    // ---------- a. two same-vendor instances keep separate identities ----------

    @Test
    @Order(1)
    void a_sameVendorInstancesPersistWithoutCrossingIdentities() throws Exception {
        UUID instanceA = id("instance-a"), instanceB = id("instance-b");
        var pinA = endpointPin("owst02-fixture-a");
        var pinB = endpointPin("owst02-fixture-b");
        assertNotEquals(pinA.digest(), pinB.digest(), "same vendor registrations stay distinct endpoints");
        assertEquals(1, ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id("credential-a"), "name", "OW-ST02 synthetic credential A", "secret", SECRET_A)).get("receipt").get("credential").get("revision").asInt());
        ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id("credential-b"), "name", "OW-ST02 synthetic credential B", "secret", SECRET_B));

        var createdA = ok("/api/v2/data-sources/connections", "POST", connectionBody("instance-a", "OW-ST02 synthetic instance A", pinA, credentialPin("credential-a"), List.of("1001")));
        var createdB = ok("/api/v2/data-sources/connections", "POST", connectionBody("instance-b", "OW-ST02 synthetic instance B", pinB, credentialPin("credential-b"), List.of("2001")));
        assertEquals(instanceA.toString(), createdA.get("receipt").get("sourceId").asString());
        assertEquals(instanceB.toString(), createdB.get("receipt").get("sourceId").asString());
        String digestA = createdA.get("connection").get("connectionDigest").asString(), digestB = createdB.get("connection").get("connectionDigest").asString();
        assertNotEquals(digestA, digestB, "connection digests never cross between instances");

        // Field-by-field read-back of both instances and their pinned connections.
        var viewA = connectionView(instanceA);
        var viewB = connectionView(instanceB);
        assertInstance(viewA, instanceA, "OW-ST02 synthetic instance A", 1, digestA);
        assertInstance(viewB, instanceB, "OW-ST02 synthetic instance B", 1, digestB);
        assertConnection(viewA.get("connection"), instanceA, 1, "owst02-fixture-a", pinA, credentialPin("credential-a"), List.of("1001"), digestA);
        assertConnection(viewB.get("connection"), instanceB, 1, "owst02-fixture-b", pinB, credentialPin("credential-b"), List.of("2001"), digestB);
        assertEquals("AVAILABLE", viewA.get("availability").asString());
        assertEquals("AVAILABLE", viewB.get("availability").asString());
        for (String secret : List.of(SECRET_A, SECRET_B)) {
            assertFalse(viewA.toString().contains(secret), "public read-back never contains secrets");
            assertFalse(viewB.toString().contains(secret));
        }

        var historyA = ok("/api/v2/data-sources/" + instanceA + "/connection/history", "GET", null).get("items");
        var historyB = ok("/api/v2/data-sources/" + instanceB + "/connection/history", "GET", null).get("items");
        assertEquals(1, historyA.size());
        assertEquals(1, historyB.size());
        assertEquals(digestA, historyA.get(0).get("connectionDigest").asString());
        assertEquals(digestB, historyB.get(0).get("connectionDigest").asString());
        assertEquals("owst02-fixture-a", historyA.get(0).get("endpoint").get("id").asString());
        assertEquals("owst02-fixture-b", historyB.get(0).get("endpoint").get("id").asString());
        assertEquals(createdA.get("receipt"), ok("/api/v2/data-sources/" + instanceA + "/connection/commands/" + instanceA, "GET", null).get("receipt"));
        assertEquals(createdB.get("receipt"), ok("/api/v2/data-sources/" + instanceB + "/connection/commands/" + instanceB, "GET", null).get("receipt"));

        // Inspections talk to their own upstream with their own credential; markers never mix.
        int readsA = upstreamA.reads.get(), readsB = upstreamB.reads.get();
        var testA = inspection(instanceA, "test", id("test-a-1"), 1, digestA);
        var testB = inspection(instanceB, "test", id("test-b-1"), 1, digestB);
        for (var view : List.of(testA, testB)) {
            assertEquals("CURRENT", view.get("validity").asString(), view.toString());
            assertTrue(view.get("inspection").get("check").get("reachable").booleanValue());
            assertEquals("READ_VERIFIED", view.get("inspection").get("check").get("statusCode").asString());
        }
        assertEquals("7.0.0", testA.get("inspection").get("check").get("reportedVersion").asString());
        assertEquals("7.0.27", testB.get("inspection").get("check").get("reportedVersion").asString());
        assertEquals(instanceA.toString(), testA.get("inspection").get("sourceId").asString());
        assertEquals(instanceB.toString(), testB.get("inspection").get("sourceId").asString());
        assertEquals(digestA, testA.get("inspection").get("connectionDigest").asString());
        assertEquals(digestB, testB.get("inspection").get("connectionDigest").asString());
        assertEquals("zabbix-jsonrpc", testA.get("inspection").get("dataMode").asString());
        assertTrue(upstreamA.reads.get() > readsA && upstreamB.reads.get() > readsB);
        assertEquals(0, upstreamA.mismatches.get(), "instance A never sends another instance's credential");
        assertEquals(0, upstreamB.mismatches.get(), "instance B never sends another instance's credential");
        assertEquals(0, upstreamA.unknown.get() + upstreamB.unknown.get());
        assertEquals(0, upstreamA.failures.get() + upstreamB.failures.get());

        var discoverA = inspection(instanceA, "discover", id("discover-a-1"), 1, digestA);
        var discoverB = inspection(instanceB, "discover", id("discover-b-1"), 1, digestB);
        assertEquals("CURRENT", discoverA.get("validity").asString());
        assertEquals("CURRENT", discoverB.get("validity").asString());
        var fieldsA = discoverA.get("inspection").get("discovery");
        var fieldsB = discoverB.get("inspection").get("discovery");
        assertEquals(2, fieldsA.get("observedRecords").asInt(), "instance A reports only its own hosts");
        assertEquals(1, fieldsB.get("observedRecords").asInt(), "instance B reports only its own hosts");
        assertNotEquals(fieldsA.get("fingerprint").asString(), fieldsB.get("fingerprint").asString());
        Map<String, JsonNode> byNameA = new LinkedHashMap<>(), byNameB = new LinkedHashMap<>();
        for (var field : fieldsA.get("fields")) byNameA.put(field.get("name").asString(), field);
        for (var field : fieldsB.get("fields")) byNameB.put(field.get("name").asString(), field);
        assertEquals("TEXT_ARRAY", byNameA.get("interfaces.ip").get("type").asString());
        assertFalse(byNameA.get("interfaces.ip").get("nullable").booleanValue());
        assertEquals("NULL", byNameB.get("interfaces.ip").get("type").asString());
        assertTrue(byNameB.get("interfaces.ip").get("nullable").booleanValue());
        assertFalse(byNameA.get("name").get("nullable").booleanValue());
        assertTrue(byNameB.get("name").get("nullable").booleanValue());

        var metricsA = inspection(instanceA, "discover-metrics", id("metrics-a-1"), 1, digestA);
        var metricsB = inspection(instanceB, "discover-metrics", id("metrics-b-1"), 1, digestB);
        assertEquals("CURRENT", metricsA.get("validity").asString());
        assertEquals("CURRENT", metricsB.get("validity").asString());
        var itemsA = metricsA.get("inspection").get("metricDiscovery").get("items");
        var itemsB = metricsB.get("inspection").get("metricDiscovery").get("items");
        assertEquals(List.of("system.cpu.util[,user]", "fixture.owst02.a.unmapped"), keys(itemsA));
        assertEquals(List.of("vm.memory.size[pavailable]"), keys(itemsB));
        assertEquals("host.cpu.usage.user", itemsA.get(0).get("mapping").get("metricKey").asString());
        assertEquals("MAPPED", itemsA.get(0).get("mappingStatus").asString());
        assertEquals("NO_MAPPING", itemsA.get(1).get("mappingStatus").asString());
        assertEquals("host.memory.available.ratio", itemsB.get(0).get("mapping").get("metricKey").asString());
        assertEquals("MAPPED", itemsB.get(0).get("mappingStatus").asString());

        // Receipts, discovery and scan records stay with their own instance.
        var inspectionsA = ok("/api/v2/data-sources/" + instanceA + "/inspections", "GET", null).get("items");
        var inspectionsB = ok("/api/v2/data-sources/" + instanceB + "/inspections", "GET", null).get("items");
        assertEquals(3, inspectionsA.size());
        assertEquals(3, inspectionsB.size());
        for (var entry : inspectionsA) {
            assertEquals(instanceA.toString(), entry.get("inspection").get("sourceId").asString());
            assertEquals(digestA, entry.get("inspection").get("connectionDigest").asString());
        }
        for (var entry : inspectionsB) {
            assertEquals(instanceB.toString(), entry.get("inspection").get("sourceId").asString());
            assertEquals(digestB, entry.get("inspection").get("connectionDigest").asString());
        }
        var checksB = ok("/api/v2/data-sources/" + instanceB + "/connection-checks", "GET", null).get("items");
        assertEquals(1, checksB.size());
        assertEquals("TEST", checksB.get(0).get("inspection").get("kind").asString());
        assertEquals(instanceB.toString(), checksB.get(0).get("inspection").get("sourceId").asString());

        var syncA = ok("/api/v2/data-sources/" + instanceA + "/connection/1/items/sync", "POST", Map.of());
        var syncB = ok("/api/v2/data-sources/" + instanceB + "/connection/1/items/sync", "POST", Map.of());
        assertEquals(instanceA.toString(), syncA.get("sourceId").asString());
        assertEquals(instanceB.toString(), syncB.get("sourceId").asString());
        assertEquals(1, syncA.get("accepted").asInt(), "instance A catalogs only its own mapped items");
        assertEquals(1, syncA.get("rejected").asInt(), "instance A keeps its own unmapped item as a rejection");
        assertEquals(1, syncB.get("accepted").asInt(), "instance B catalogs only its own mapped items");
        assertEquals(0, syncB.get("rejected").asInt());
        assertEquals(2, syncA.get("fetched").asInt());
        assertEquals(1, syncB.get("fetched").asInt(), "scan counts never mix the two instances");
        assertNotEquals(syncA.get("scopeDigest").asString(), syncB.get("scopeDigest").asString());
        var runsA = ok("/api/v2/data-sources/" + instanceA + "/connection/1/items/runs", "GET", null);
        var runsB = ok("/api/v2/data-sources/" + instanceB + "/connection/1/items/runs", "GET", null);
        assertEquals(1, runsA.get("items").size());
        assertEquals(1, runsB.get("items").size());
        var runA = runsA.get("items").get(0);
        var runB = runsB.get("items").get(0);
        assertEquals(instanceA.toString(), runA.get("sourceId").asString());
        assertEquals(instanceB.toString(), runB.get("sourceId").asString());
        assertEquals(digestA, runA.get("connectionDigest").asString());
        assertEquals(digestB, runB.get("connectionDigest").asString());
        assertEquals(syncA.get("scopeDigest").asString(), runA.get("scopeDigest").asString());
        assertEquals(syncB.get("scopeDigest").asString(), runB.get("scopeDigest").asString());
        assertEquals("item", runA.get("objectType").asString());
        assertEquals(2, runA.get("fetched").asInt());
        assertEquals(1, runA.get("accepted").asInt());
        assertEquals(1, runA.get("rejected").asInt());
        assertEquals(1, runB.get("fetched").asInt());
        assertEquals(1, runB.get("accepted").asInt());
        assertEquals(0, runB.get("rejected").asInt());
    }

    // ---------- e. failed / duplicate confirmations leave no duplicate or half instances ----------

    @Test
    @Order(2)
    void e_confirmationBoundariesLeaveNoDuplicateOrHalfInstances() throws Exception {
        var pinA = endpointPin("owst02-fixture-a");
        ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id("credential-c"), "name", "OW-ST02 synthetic credential C", "secret", "owst02-synthetic-upstream-c-token"));
        UUID instanceC = id("instance-c");
        var bodyC = connectionBody("instance-c", "OW-ST02 synthetic instance C", pinA, credentialPin("credential-c"), List.of("1001"));

        // Duplicate submission of the same confirmation returns the original receipt.
        var firstC = ok("/api/v2/data-sources/connections", "POST", bodyC);
        assertEquals(firstC, ok("/api/v2/data-sources/connections", "POST", bodyC));
        assertEquals(1, ok("/api/v2/data-sources/" + instanceC + "/connection/history", "GET", null).get("items").size());
        var changedC = new LinkedHashMap<>(bodyC);
        changedC.put("name", "OW-ST02 synthetic instance C changed");
        assertEquals(409, call("/api/v2/data-sources/connections", "POST", changedC).statusCode(), "same requestId with different content is an explicit conflict");
        assertEquals(1, ok("/api/v2/data-sources/" + instanceC + "/connection/history", "GET", null).get("items").size());

        // A failed confirmation leaves no half instance.
        var unknownCredential = call("/api/v2/data-sources/connections", "POST", connectionBody("instance-failed-unknown", "OW-ST02 synthetic instance failed", pinA, credentialPin("credential-unknown"), List.of("1001")));
        assertEquals(404, unknownCredential.statusCode(), unknownCredential.body());
        var unknownEndpoint = call("/api/v2/data-sources/connections", "POST", connectionBody("instance-failed-endpoint", "OW-ST02 synthetic instance failed", new SourceEndpoint.Pin("owst02-fixture-unknown", "sha256:" + "0".repeat(64)), credentialPin("credential-c"), List.of("1001")));
        assertEquals(404, unknownEndpoint.statusCode(), unknownEndpoint.body());
        for (String label : List.of("instance-failed-unknown", "instance-failed-endpoint")) {
            UUID failedId = id(label);
            assertEquals(404, call("/api/v2/data-sources/" + failedId, "GET", null).statusCode());
            assertEquals(404, call("/api/v2/data-sources/" + failedId + "/connection", "GET", null).statusCode());
            assertEquals(404, call("/api/v2/data-sources/" + failedId + "/connection/history", "GET", null).statusCode());
            assertEquals(404, call("/api/v1/integrations/sources/" + failedId, "GET", null).statusCode());
        }

        // Concurrent duplicate submissions keep one instance and are idempotent or explicit 409.
        UUID instanceE = id("instance-e");
        var bodyE = connectionBody("instance-e", "OW-ST02 synthetic instance E", pinA, credentialPin("credential-c"), List.of("1001"));
        var statuses = new CopyOnWriteArrayList<Integer>();
        var bodies = new CopyOnWriteArrayList<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    var response = call("/api/v2/data-sources/connections", "POST", bodyE);
                    statuses.add(response.statusCode());
                    bodies.add(response.body());
                } catch (Exception failure) {
                    statuses.add(0);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(List.of(200, 200), new ArrayList<>(statuses), "concurrent duplicate confirmations are idempotent inside the tenant transaction boundary: " + statuses);
        assertEquals(bodies.get(0), bodies.get(1), "both concurrent confirmations return the same original receipt");
        var replayE = call("/api/v2/data-sources/connections", "POST", bodyE);
        assertEquals(200, replayE.statusCode(), replayE.body());
        assertEquals(bodies.get(0), replayE.body(), "sequential replay returns the original receipt");
        assertEquals(1, ok("/api/v2/data-sources/" + instanceE + "/connection/history", "GET", null).get("items").size());
        assertEquals(1, countInstances(instanceE), "concurrent confirmations leave exactly one instance");

        // Legacy confirmation with a target seeds its persistent draft in the same transaction.
        var catalog = ok("/api/v1/integrations/sources", "GET", null);
        String manualDigest = catalog.get("types").get(1).get("connection").get("digest").asString();
        var model = new BuiltinCatalog().definitions().stream().filter(m -> m.id().equals("builtin.host")).findFirst().orElseThrow();
        var confirmD = new LinkedHashMap<String, Object>();
        confirmD.put("requestId", id("confirm-d").toString());
        confirmD.put("name", "OW-ST02 manual confirmation");
        confirmD.put("description", "OW-ST02 synthetic manual sample confirmation");
        confirmD.put("source", Map.of("kind", "MANUAL_SAMPLE", "instanceId", "manual"));
        confirmD.put("connectionDigest", manualDigest);
        confirmD.put("target", Map.of("id", model.id(), "revision", 1, "digest", model.digest()));
        var confirmedD = ok("/api/v1/integrations/sources/confirm", "POST", confirmD);
        assertEquals("DRAFT", confirmedD.get("workflow").get("state").asString());
        assertEquals("source-" + id("confirm-d"), confirmedD.get("workflow").get("definition").get("id").asString());
        assertEquals("MANUAL_SAMPLE", confirmedD.get("workflow").get("definition").get("source").get("kind").asString());
        assertEquals(confirmedD, ok("/api/v1/integrations/sources/confirm", "POST", confirmD), "duplicate confirmation returns the original receipt and no second draft");
        assertEquals(1, ok("/api/v1/integrations/sources/" + id("confirm-d"), "GET", null).get("workflow").get("editVersion").asInt());
        var changedD = new LinkedHashMap<>(confirmD);
        changedD.put("name", "OW-ST02 manual confirmation changed");
        assertEquals(409, call("/api/v1/integrations/sources/confirm", "POST", changedD).statusCode());

        // Failed confirmations leave neither an instance nor a draft.
        var badDigest = new LinkedHashMap<>(confirmD);
        badDigest.put("requestId", id("confirm-bad-digest").toString());
        badDigest.put("connectionDigest", "sha256:" + "0".repeat(64));
        assertEquals(503, call("/api/v1/integrations/sources/confirm", "POST", badDigest).statusCode());
        var badModel = new LinkedHashMap<>(confirmD);
        badModel.put("requestId", id("confirm-bad-model").toString());
        badModel.put("target", Map.of("id", "custom.owst02_missing", "revision", 1, "digest", "sha256:" + "0".repeat(64)));
        int badModelState = call("/api/v1/integrations/sources/confirm", "POST", badModel).statusCode();
        assertTrue(Set.of(400, 404, 409).contains(badModelState), "unknown target model is an explicit rejection, got " + badModelState);
        for (UUID failed : List.of(id("confirm-bad-digest"), id("confirm-bad-model"))) {
            assertEquals(404, call("/api/v1/integrations/sources/" + failed, "GET", null).statusCode());
            assertEquals(404, call("/api/v1/integrations/sources/" + failed + "/continuation", "GET", null).statusCode());
        }
    }

    int countInstances(UUID instanceId) throws Exception {
        int found = 0;
        for (var item : ok("/api/v2/data-sources", "GET", null).get("items")) {
            if (item.get("id").asString().equals(instanceId.toString())) found++;
        }
        return found;
    }

    // ---------- f. each confirmation enters its own persistent draft ----------

    @Test
    @Order(3)
    void f_confirmationEntersTheCorrespondingPersistentDraft() throws Exception {
        UUID instanceA = id("instance-a"), instanceB = id("instance-b");
        var currentA = connectionView(instanceA).get("connection");
        var currentB = connectionView(instanceB).get("connection");
        String digestA = currentA.get("connectionDigest").asString();
        String digestB = currentB.get("connectionDigest").asString();
        var sourceA = new WorkflowDefinition.Source("ZABBIX_HOST", "connection-" + instanceA, new WorkflowDefinition.ConfigurationPin(instanceA, 1, digestA));
        var sourceB = new WorkflowDefinition.Source("ZABBIX_HOST", "connection-" + instanceB, new WorkflowDefinition.ConfigurationPin(instanceB, 1, digestB));

        var savedA = saveDraft("source-" + instanceA, sourceA, 0);
        var savedB = saveDraft("source-" + instanceB, sourceB, 0);
        assertEquals(200, savedA.statusCode(), savedA.body());
        assertEquals(200, savedB.statusCode(), savedB.body());
        assertEquals(1, CatalogJson.JSON.readTree(savedA.body()).get("editVersion").asInt());

        for (Object[] instance : List.<Object[]>of(new Object[]{instanceA, digestA}, new Object[]{instanceB, digestB})) {
            UUID instanceId = (UUID) instance[0];
            String digest = (String) instance[1];
            var continuation = ok("/api/v1/integrations/sources/" + instanceId + "/continuation", "GET", null);
            assertEquals(instanceId.toString(), continuation.get("setupId").asString());
            var definition = continuation.get("workflow").get("definition");
            assertEquals("source-" + instanceId, definition.get("id").asString());
            var pin = definition.get("source").get("configuration");
            assertEquals(instanceId.toString(), pin.get("sourceId").asString(), "the draft pin belongs to its own instance");
            assertEquals(1, pin.get("revision").asInt());
            assertEquals(digest, pin.get("digest").asString(), "the draft pin is the confirmed connection digest");
            assertEquals("connection-" + instanceId, definition.get("source").get("instanceId").asString());
            var readBack = ok("/api/v1/integrations/sources/" + instanceId, "GET", null);
            assertEquals(definition, readBack.get("workflow").get("definition"));
        }

        // A duplicate draft save conflicts and never creates a second draft.
        assertEquals(409, saveDraft("source-" + instanceA, sourceA, 0).statusCode());
        var readA = ok("/api/v1/integrations/sources/" + instanceA, "GET", null);
        assertEquals(1, readA.get("workflow").get("editVersion").asInt());
        assertEquals("source-" + instanceA, readA.get("workflow").get("definition").get("id").asString());

        // A failed draft save leaves no draft behind for its instance.
        UUID instanceE = id("instance-e");
        String digestE = connectionView(instanceE).get("connection").get("connectionDigest").asString();
        assertEquals(409, saveDraft("source-" + instanceE,
            new WorkflowDefinition.Source("ZABBIX_HOST", "connection-" + instanceE, new WorkflowDefinition.ConfigurationPin(instanceE, 1, digestE)), 5).statusCode());
        assertTrue(ok("/api/v1/integrations/sources/" + instanceE + "/continuation", "GET", null).get("workflow").isNull());
    }

    // ---------- c. connection receipts invalidate with the configuration ----------

    @Test
    @Order(4)
    void c_connectionReceiptsInvalidateWhenTheConfigurationChanges() throws Exception {
        UUID instanceA = id("instance-a");
        var currentA = connectionView(instanceA).get("connection");
        String digestA = currentA.get("connectionDigest").asString();
        var creationBefore = ok("/api/v2/data-sources/" + instanceA + "/connection/commands/" + instanceA, "GET", null).get("receipt");
        var before = inspection(instanceA, "test", id("test-a-rev1"), 1, digestA);
        assertEquals("CURRENT", before.get("validity").asString(), before.toString());

        var changed = ok("/api/v2/data-sources/" + instanceA + "/connection", "PATCH",
            connectionBody("edit-a-scope", "OW-ST02 synthetic instance A", endpointPin("owst02-fixture-a"), credentialPin(currentA), List.of("1001", "1002"), 1));
        String digestA2 = changed.get("connection").get("connectionDigest").asString();
        assertNotEquals(digestA, digestA2);
        assertEquals(2, changed.get("connection").get("revision").asInt());
        assertEquals(2, changed.get("receipt").get("instance").get("configurationRevision").asInt());

        // The old connection receipt is preserved but no longer presented as current.
        var stale = ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id("test-a-rev1"), "GET", null).get("view");
        assertEquals("STALE", stale.get("validity").asString(), stale.toString());
        assertEquals(digestA, stale.get("inspection").get("connectionDigest").asString(), "the original record keeps its own connection digest");
        assertEquals("READ_VERIFIED", stale.get("inspection").get("check").get("statusCode").asString());
        var listed = ok("/api/v2/data-sources/" + instanceA + "/inspections", "GET", null).get("items");
        Map<String, String> validity = new LinkedHashMap<>();
        for (var entry : listed) validity.put(entry.get("inspection").get("requestId").asString(), entry.get("validity").asString());
        for (String label : List.of("test-a-1", "discover-a-1", "metrics-a-1")) assertEquals("STALE", validity.get(id(label).toString()), label + " is not current after the configuration change");

        // The new configuration gets its own current receipt; the creation receipt stays immutable.
        var after = inspection(instanceA, "test", id("test-a-rev2"), 2, digestA2);
        assertEquals("CURRENT", after.get("validity").asString(), after.toString());
        assertEquals(digestA2, after.get("inspection").get("connectionDigest").asString());
        assertEquals(creationBefore, ok("/api/v2/data-sources/" + instanceA + "/connection/commands/" + instanceA, "GET", null).get("receipt"));
        var history = ok("/api/v2/data-sources/" + instanceA + "/connection/history", "GET", null).get("items");
        assertEquals(2, history.size());
        Map<Integer, String> revisions = new LinkedHashMap<>();
        for (var entry : history) revisions.put(entry.get("revision").asInt(), entry.get("connectionDigest").asString());
        assertEquals(digestA, revisions.get(1));
        assertEquals(digestA2, revisions.get(2));
    }

    // ---------- b. one instance's failure never crosses to the other ----------

    @Test
    @Order(5)
    void b_instanceFailureStaysInsideItsOwnIdentity() throws Exception {
        UUID instanceA = id("instance-a"), instanceB = id("instance-b");
        var currentA = connectionView(instanceA).get("connection");
        var currentB = connectionView(instanceB).get("connection");
        int revisionA = currentA.get("revision").asInt();
        String digestA = currentA.get("connectionDigest").asString(), digestB = currentB.get("connectionDigest").asString();
        upstreamB.stop();
        int readsA = upstreamA.reads.get(), readsB = upstreamB.reads.get();

        var failedB = inspection(instanceB, "test", id("test-b-down"), 1, digestB);
        assertEquals("UNVERIFIED", failedB.get("validity").asString(), failedB.toString());
        assertFalse(failedB.get("inspection").get("check").get("reachable").booleanValue());
        assertEquals("UNREACHABLE", failedB.get("inspection").get("check").get("statusCode").asString());
        assertEquals(readsB, upstreamB.reads.get(), "the failed probe stays on instance B's own endpoint");
        assertEquals(readsA, upstreamA.reads.get(), "instance B's outage never touches instance A's endpoint");

        var aliveA = inspection(instanceA, "test", id("test-a-after-b"), revisionA, digestA);
        assertEquals("CURRENT", aliveA.get("validity").asString(), aliveA.toString());
        assertTrue(aliveA.get("inspection").get("check").get("reachable").booleanValue());
        assertTrue(upstreamA.reads.get() > readsA);

        // Credential revocation invalidates only instance B's availability; instance A stays usable.
        assertEquals(200, call("/api/v2/data-sources/credentials/" + id("credential-b") + "/versions/1/revoke", "POST", Map.of("requestId", id("revoke-b-v1"), "expectedEditVersion", 1)).statusCode());
        assertEquals("UNAVAILABLE", connectionView(instanceB).get("availability").asString());
        assertEquals("AVAILABLE", connectionView(instanceA).get("availability").asString());
        var revokedView = ok("/api/v2/data-sources/" + instanceB + "/inspections/" + id("test-b-1"), "GET", null).get("view");
        assertEquals("STALE", revokedView.get("validity").asString());
        assertEquals("7.0.27", revokedView.get("inspection").get("check").get("reportedVersion").asString(), "revocation changes reusability but preserves the original metadata record");
        assertEquals("CURRENT", ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id("test-a-after-b"), "GET", null).get("view").get("validity").asString());
        assertEquals(503, call("/api/v2/data-sources/" + instanceB + "/test", "POST", Map.of("requestId", id("test-b-blocked"), "configurationRevision", 1, "connectionDigest", digestB)).statusCode());

        // A confirmation pinned to the revoked credential version is an explicit rejection and leaves nothing.
        var revokedCreate = call("/api/v2/data-sources/connections", "POST",
            connectionBody("instance-failed-revoked", "OW-ST02 synthetic instance failed", endpointPin("owst02-fixture-a"), credentialPin("credential-b"), List.of("1001")));
        assertEquals(409, revokedCreate.statusCode(), revokedCreate.body());
        assertEquals(404, call("/api/v2/data-sources/" + id("instance-failed-revoked"), "GET", null).statusCode());
        assertEquals(404, call("/api/v2/data-sources/" + id("instance-failed-revoked") + "/connection", "GET", null).statusCode());
    }

    // ---------- d. restart / new-connection read-back (strictly read-only) ----------

    @Test
    @Order(6)
    void d_restartedProcessReadsBackInstancesReceiptsAndDrafts() throws Exception {
        UUID instanceA = id("instance-a"), instanceB = id("instance-b");
        var viewA = connectionView(instanceA);
        var viewB = connectionView(instanceB);
        String digestA2 = viewA.get("connection").get("connectionDigest").asString();
        String digestB1 = viewB.get("connection").get("connectionDigest").asString();
        assertInstance(viewA, instanceA, "OW-ST02 synthetic instance A", 2, digestA2);
        assertInstance(viewB, instanceB, "OW-ST02 synthetic instance B", 1, digestB1);
        assertConnection(viewA.get("connection"), instanceA, 2, "owst02-fixture-a", endpointPin("owst02-fixture-a"), credentialPin("credential-a"), List.of("1001", "1002"), digestA2);
        assertConnection(viewB.get("connection"), instanceB, 1, "owst02-fixture-b", endpointPin("owst02-fixture-b"), credentialPin("credential-b"), List.of("2001"), digestB1);
        assertEquals("AVAILABLE", viewA.get("availability").asString());
        assertEquals("UNAVAILABLE", viewB.get("availability").asString());
        var historyA = ok("/api/v2/data-sources/" + instanceA + "/connection/history", "GET", null).get("items");
        assertEquals(2, historyA.size());
        assertEquals(1, ok("/api/v2/data-sources/" + instanceB + "/connection/history", "GET", null).get("items").size());

        // Receipt annotations survive the restart: invalidated receipts never read back as current.
        for (String label : List.of("test-a-rev2", "test-a-after-b")) assertValidity(instanceA, label, Set.of("CURRENT", "EXPIRED"));
        for (String label : List.of("test-a-1", "discover-a-1", "metrics-a-1", "test-a-rev1")) assertValidity(instanceA, label, Set.of("STALE", "EXPIRED"));
        for (String label : List.of("test-b-1", "discover-b-1", "metrics-b-1", "test-b-down")) assertValidity(instanceB, label, Set.of("STALE", "EXPIRED"));
        var discoveryB = ok("/api/v2/data-sources/" + instanceB + "/inspections/" + id("discover-b-1"), "GET", null).get("view").get("inspection").get("discovery");
        assertEquals(1, discoveryB.get("observedRecords").asInt());
        assertEquals(6, ok("/api/v2/data-sources/" + instanceA + "/inspections", "GET", null).get("items").size());
        assertEquals(4, ok("/api/v2/data-sources/" + instanceB + "/inspections", "GET", null).get("items").size());

        // Confirmation-to-draft links survive with their exact pinned configuration: each draft
        // pin is the configuration revision that its own confirmation recorded.
        for (Object[] instance : List.<Object[]>of(new Object[]{instanceA, 1, createdReceiptDigest(instanceA, instanceA)}, new Object[]{instanceB, 1, createdReceiptDigest(instanceB, instanceB)})) {
            UUID instanceId = (UUID) instance[0];
            var definition = ok("/api/v1/integrations/sources/" + instanceId + "/continuation", "GET", null).get("workflow").get("definition");
            var pin = definition.get("source").get("configuration");
            assertEquals(instanceId.toString(), pin.get("sourceId").asString());
            assertEquals(((Number) instance[1]).intValue(), pin.get("revision").asInt());
            assertEquals(instance[2], pin.get("digest").asString());
        }
        assertEquals("MANUAL_SAMPLE", ok("/api/v1/integrations/sources/" + id("confirm-d") + "/continuation", "GET", null).get("workflow").get("definition").get("source").get("kind").asString());

        // Scan records stay with their own instance and scope.
        var runA = ok("/api/v2/data-sources/" + instanceA + "/connection/1/items/runs", "GET", null).get("items").get(0);
        var runB = ok("/api/v2/data-sources/" + instanceB + "/connection/1/items/runs", "GET", null).get("items").get(0);
        assertEquals(instanceA.toString(), runA.get("sourceId").asString());
        assertEquals(instanceB.toString(), runB.get("sourceId").asString());
        assertEquals(2, runA.get("fetched").asInt());
        assertEquals(1, runA.get("accepted").asInt());
        assertEquals(1, runB.get("fetched").asInt());
        assertEquals(1, runB.get("accepted").asInt());
        assertNotEquals(runA.get("scopeDigest").asString(), runB.get("scopeDigest").asString());

        // Zero side effects of the whole confirmation/configuration chain.
        var entities = ok("/api/v1/entities/page?limit=50", "GET", null).get("items");
        assertEquals(0, entities.size(), "no entity instance is written by confirmation or configuration");
        var runtime = ok("/api/v1/integrations/workflows/runtime", "GET", null);
        assertEquals(0, runtime.get("tasks").size(), "no collection task is started");
        assertEquals(0, runtime.get("executions").size(), "no workflow execution is started");
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))) {
            for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
                try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
                    statement.setString(1, TENANT);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1), table + " must stay empty: the confirmation chain never calls AI or models");
                    }
                }
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    void assertValidity(UUID instanceId, String label, Set<String> allowed) throws Exception {
        var view = ok("/api/v2/data-sources/" + instanceId + "/inspections/" + id(label), "GET", null).get("view");
        assertTrue(allowed.contains(view.get("validity").asString()), label + " expected " + allowed + " but was " + view.get("validity").asString());
    }

    String createdReceiptDigest(UUID instanceId, UUID requestId) throws Exception {
        return ok("/api/v2/data-sources/" + instanceId + "/connection/commands/" + requestId, "GET", null).get("receipt").get("instance").get("connectionDigest").asString();
    }

    // ---------- shared assertions ----------

    static List<String> keys(JsonNode items) {
        var values = new ArrayList<String>();
        for (var item : items) values.add(item.get("sourceKey").asString());
        return values;
    }

    static void assertInstance(JsonNode view, UUID id, String name, int revision, String digest) {
        var instance = view.get("instance");
        assertEquals(id.toString(), instance.get("id").asString());
        assertEquals(name, instance.get("name").asString());
        assertEquals("ZABBIX_HOST", instance.get("source").get("kind").asString());
        assertEquals("connection-" + id, instance.get("source").get("instanceId").asString());
        assertEquals(revision, instance.get("configurationRevision").asInt());
        assertEquals(digest, instance.get("connectionDigest").asString());
        assertEquals("zabbix-jsonrpc", instance.get("dataMode").asString());
        assertEquals("ACTIVE", instance.get("state").asString());
    }

    static void assertConnection(JsonNode connection, UUID sourceId, int revision, String endpointId, SourceEndpoint.Pin endpointPin, SourceCredential.Pin credentialPin, List<String> groups, String digest) {
        assertEquals(sourceId.toString(), connection.get("sourceId").asString());
        assertEquals(revision, connection.get("revision").asInt());
        assertEquals("host-jsonrpc-v2", connection.get("connectorVersion").asString());
        assertEquals(endpointId, connection.get("endpoint").get("id").asString());
        assertEquals(endpointPin.digest(), connection.get("endpoint").get("digest").asString());
        assertEquals(credentialPin.credentialId().toString(), connection.get("credentialPin").get("credentialId").asString());
        assertEquals(credentialPin.revision(), connection.get("credentialPin").get("revision").asInt());
        assertEquals(credentialPin.versionId().toString(), connection.get("credentialPin").get("versionId").asString());
        assertEquals(groups, readGroups(connection.get("hostGroupIds")));
        assertEquals(digest, connection.get("connectionDigest").asString());
    }

    static List<String> readGroups(JsonNode value) {
        var groups = new ArrayList<String>();
        for (var group : value) groups.add(group.asString());
        return groups;
    }
}
