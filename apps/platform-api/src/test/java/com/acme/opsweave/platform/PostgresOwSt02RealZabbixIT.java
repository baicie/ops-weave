package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.integration.domain.SourceCredential;
import com.acme.opsweave.integration.domain.SourceEndpoint;
import com.acme.opsweave.platform.catalog.CatalogJson;
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
 * OW-ST02 real-source variant against an actual local Zabbix 7.0.27 JSON-RPC endpoint, run only
 * when {@code OPSWEAVE_TEST_ZABBIX_URL} and {@code OPSWEAVE_TEST_ZABBIX_TOKEN} are provided. Two
 * same-vendor registered instances use two distinct endpoint registrations and two distinct
 * credential pins over the one real server the environment provides; identity separation is
 * asserted on configuration digests, credential pins, scope digests and per-instance receipts.
 * The token is read from the environment only and is never logged, committed or echoed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "server.address=127.0.0.1",
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=owst02-real-zabbix-fixture-only-32chars",
    "opsweave.auth.dev.subject=owst02-real-author",
    "opsweave.auth.dev.permissions=source.sync,source.configure,entity.read,entity.manage,metric.read",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=owst02-deploy-fixture",
    "opsweave.inventory.store=postgres"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_ZABBIX_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_ZABBIX_TOKEN", matches = ".+")
class PostgresOwSt02RealZabbixIT {
    static final String TOKEN = "owst02-real-zabbix-fixture-only-32chars";
    static final String SEED = System.getenv("OPSWEAVE_OW_ST02_SEED") == null ? UUID.randomUUID().toString() : System.getenv("OPSWEAVE_OW_ST02_SEED");
    static final String TENANT = "ow-st02-real-" + SEED;
    static final String SECRET = System.getenv("OPSWEAVE_TEST_ZABBIX_TOKEN");
    static final String ADDRESS = System.getenv("OPSWEAVE_TEST_ZABBIX_URL");

    static Path registry, keyring;

    static void setup() {
        try {
            if (registry != null) return;
            registry = Files.writeString(Files.createTempFile("opsweave-owst02-real-endpoints-", ".json"), CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion", "2.0", "endpoints", List.of(
                Map.of("id", "owst02-real-a", "name", "OW-ST02 real Zabbix registration A", "connectorKind", "ZABBIX_HOST", "address", ADDRESS, "tenants", List.of(TENANT)),
                Map.of("id", "owst02-real-b", "name", "OW-ST02 real Zabbix registration B", "connectorKind", "ZABBIX_HOST", "address", ADDRESS, "tenants", List.of(TENANT))))));
            keyring = Files.writeString(Files.createTempFile("opsweave-owst02-real-keyring-", ".json"), "{\"activeKeyId\":\"owst02-fixture-key\",\"keys\":{\"owst02-fixture-key\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}}");
        } catch (Exception failure) {
            throw new IllegalStateException("OW-ST02 real endpoint setup failed", failure);
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
        if (registry != null) Files.deleteIfExists(registry);
        if (keyring != null) Files.deleteIfExists(keyring);
    }

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    static UUID id(String label) { return UUID.nameUUIDFromBytes((TENANT + "|" + label).getBytes(StandardCharsets.UTF_8)); }

    HttpResponse<String> call(String path, String method, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
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

    SourceEndpoint.Pin endpointPin(String endpointId) throws Exception {
        var endpoint = ok("/api/v2/data-sources/endpoints/" + endpointId, "GET", null).get("endpoint");
        return new SourceEndpoint.Pin(endpoint.get("id").asString(), endpoint.get("digest").asString());
    }

    static SourceCredential.Pin credentialPin(String label, int revision, UUID versionId) { return new SourceCredential.Pin(id(label), revision, versionId); }

    Map<String, Object> connectionBody(String label, String name, SourceEndpoint.Pin endpointPin, SourceCredential.Pin credentialPin, List<String> groups, Integer expectedEditVersion) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", id(label).toString());
        if (expectedEditVersion != null) body.put("expectedEditVersion", expectedEditVersion);
        body.put("name", name);
        body.put("description", "OW-ST02 real local Zabbix registered connection");
        body.put("endpointPin", Map.of("id", endpointPin.id(), "digest", endpointPin.digest()));
        body.put("credentialPin", Map.of("credentialId", credentialPin.credentialId().toString(), "revision", credentialPin.revision(), "versionId", credentialPin.versionId().toString()));
        body.put("hostGroupIds", groups);
        return body;
    }

    JsonNode inspection(UUID instanceId, String path, UUID requestId, int revision, String digest) throws Exception {
        return ok("/api/v2/data-sources/" + instanceId + "/" + path, "POST", Map.of("requestId", requestId, "configurationRevision", revision, "connectionDigest", digest)).get("view");
    }

    @Test
    @Order(1)
    void a_twoRealVendorInstancesKeepSeparateIdentities() throws Exception {
        UUID instanceA = id("real-a"), instanceB = id("real-b");
        var pinA = endpointPin("owst02-real-a");
        var pinB = endpointPin("owst02-real-b");
        assertNotEquals(pinA.digest(), pinB.digest(), "two distinct endpoint registrations of the same vendor");
        assertEquals(1, ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id("real-cred-a"), "name", "OW-ST02 real credential A", "secret", SECRET)).get("receipt").get("credential").get("revision").asInt());
        ok("/api/v2/data-sources/credentials", "POST", Map.of("requestId", id("real-cred-b"), "name", "OW-ST02 real credential B", "secret", SECRET));
        var pinCredA = credentialPin("real-cred-a", 1, id("real-cred-a"));
        var pinCredB = credentialPin("real-cred-b", 1, id("real-cred-b"));

        var createdA = ok("/api/v2/data-sources/connections", "POST", connectionBody("real-a", "OW-ST02 real instance A", pinA, pinCredA, List.of("22"), null));
        var createdB = ok("/api/v2/data-sources/connections", "POST", connectionBody("real-b", "OW-ST02 real instance B", pinB, pinCredB, List.of("22"), null));
        String digestA = createdA.get("connection").get("connectionDigest").asString(), digestB = createdB.get("connection").get("connectionDigest").asString();
        assertNotEquals(digestA, digestB, "connection digests never cross between instances");

        var viewA = ok("/api/v2/data-sources/" + instanceA + "/connection", "GET", null);
        var viewB = ok("/api/v2/data-sources/" + instanceB + "/connection", "GET", null);
        assertEquals("connection-" + instanceA, viewA.get("instance").get("source").get("instanceId").asString());
        assertEquals("connection-" + instanceB, viewB.get("instance").get("source").get("instanceId").asString());
        assertEquals("zabbix-jsonrpc", viewA.get("instance").get("dataMode").asString());
        assertEquals("zabbix-jsonrpc", viewB.get("instance").get("dataMode").asString());
        assertEquals("owst02-real-a", viewA.get("connection").get("endpoint").get("id").asString());
        assertEquals("owst02-real-b", viewB.get("connection").get("endpoint").get("id").asString());
        assertEquals(pinCredA.versionId().toString(), viewA.get("connection").get("credentialPin").get("versionId").asString());
        assertEquals(pinCredB.versionId().toString(), viewB.get("connection").get("credentialPin").get("versionId").asString());
        assertEquals("AVAILABLE", viewA.get("availability").asString());
        assertEquals("AVAILABLE", viewB.get("availability").asString());
        assertFalse(viewA.toString().contains(SECRET) || viewB.toString().contains(SECRET), "public read-back never contains the real token");

        var testA = inspection(instanceA, "test", id("real-test-a"), 1, digestA);
        var testB = inspection(instanceB, "test", id("real-test-b"), 1, digestB);
        for (var view : List.of(testA, testB)) {
            assertEquals("CURRENT", view.get("validity").asString(), view.toString());
            assertTrue(view.get("inspection").get("check").get("reachable").booleanValue());
            assertEquals("READ_VERIFIED", view.get("inspection").get("check").get("statusCode").asString());
            assertEquals("7.0.27", view.get("inspection").get("check").get("reportedVersion").asString(), "the real server reports its own version");
        }
        assertEquals(instanceA.toString(), testA.get("inspection").get("sourceId").asString());
        assertEquals(instanceB.toString(), testB.get("inspection").get("sourceId").asString());
        assertEquals(digestA, testA.get("inspection").get("connectionDigest").asString());
        assertEquals(digestB, testB.get("inspection").get("connectionDigest").asString());

        var discoverA = inspection(instanceA, "discover", id("real-discover-a"), 1, digestA);
        var discoverB = inspection(instanceB, "discover", id("real-discover-b"), 1, digestB);
        // The bounded first host page is honest about coverage: never a claimed complete list.
        for (var view : List.of(discoverA, discoverB)) {
            var discovery = view.get("inspection").get("discovery");
            assertEquals(5, discovery.get("observedRecords").asInt(), "bounded first host page");
            if (discovery.get("complete").booleanValue()) {
                assertEquals("CURRENT", view.get("validity").asString());
            } else {
                assertEquals("INCOMPLETE", discovery.get("statusCode").asString());
                assertEquals("UNVERIFIED", view.get("validity").asString(), "incomplete coverage is never presented as reusable");
            }
        }
        assertEquals(discoverA.get("inspection").get("discovery").get("fingerprint").asString(), discoverB.get("inspection").get("discovery").get("fingerprint").asString(), "the same real source page is observed per instance without mixing identities");

        var metricsA = inspection(instanceA, "discover-metrics", id("real-metrics-a"), 1, digestA);
        var metricsB = inspection(instanceB, "discover-metrics", id("real-metrics-b"), 1, digestB);
        for (var view : List.of(metricsA, metricsB)) {
            assertEquals("CURRENT", view.get("validity").asString(), view.toString());
            var keys = new ArrayList<String>();
            for (var item : view.get("inspection").get("metricDiscovery").get("items")) keys.add(item.get("sourceKey").asString());
            assertTrue(keys.contains("system.cpu.util[,user]"), keys.toString());
            assertTrue(keys.contains("vm.memory.size[pavailable]"), keys.toString());
            assertTrue(keys.contains("system.uptime"), keys.toString());
        }

        // Receipt and scan records belong to their own instance and scope.
        for (var instance : List.of(new Object[]{instanceA, digestA}, new Object[]{instanceB, digestB})) {
            UUID instanceId = (UUID) instance[0];
            String digest = (String) instance[1];
            for (var entry : ok("/api/v2/data-sources/" + instanceId + "/inspections", "GET", null).get("items")) {
                assertEquals(instanceId.toString(), entry.get("inspection").get("sourceId").asString());
                assertEquals(digest, entry.get("inspection").get("connectionDigest").asString());
            }
        }
        var syncA = ok("/api/v2/data-sources/" + instanceA + "/connection/1/items/sync", "POST", Map.of());
        var syncB = ok("/api/v2/data-sources/" + instanceB + "/connection/1/items/sync", "POST", Map.of());
        assertEquals(instanceA.toString(), syncA.get("sourceId").asString());
        assertEquals(instanceB.toString(), syncB.get("sourceId").asString());
        assertEquals(syncA.get("fetched").asInt(), syncB.get("fetched").asInt());
        assertTrue(syncA.get("fetched").asInt() > 0);
        assertNotEquals(syncA.get("scopeDigest").asString(), syncB.get("scopeDigest").asString(), "scan scopes stay per instance");
        var runA = ok("/api/v2/data-sources/" + instanceA + "/connection/1/items/runs", "GET", null).get("items").get(0);
        var runB = ok("/api/v2/data-sources/" + instanceB + "/connection/1/items/runs", "GET", null).get("items").get(0);
        assertEquals(instanceA.toString(), runA.get("sourceId").asString());
        assertEquals(instanceB.toString(), runB.get("sourceId").asString());
        assertEquals(syncA.get("scopeDigest").asString(), runA.get("scopeDigest").asString());
        assertEquals(syncB.get("scopeDigest").asString(), runB.get("scopeDigest").asString());
    }

    @Test
    @Order(2)
    void b_realCredentialRevocationNeverCrossesInstances() throws Exception {
        UUID instanceA = id("real-a"), instanceB = id("real-b");
        var currentA = ok("/api/v2/data-sources/" + instanceA + "/connection", "GET", null).get("connection");
        String digestA = currentA.get("connectionDigest").asString(), digestB = ok("/api/v2/data-sources/" + instanceB + "/connection", "GET", null).get("connection").get("connectionDigest").asString();
        assertEquals(200, call("/api/v2/data-sources/credentials/" + id("real-cred-b") + "/versions/1/revoke", "POST", Map.of("requestId", id("real-revoke-b"), "expectedEditVersion", 1)).statusCode());
        assertEquals("UNAVAILABLE", ok("/api/v2/data-sources/" + instanceB + "/connection", "GET", null).get("availability").asString());
        assertEquals("AVAILABLE", ok("/api/v2/data-sources/" + instanceA + "/connection", "GET", null).get("availability").asString());
        assertEquals("STALE", ok("/api/v2/data-sources/" + instanceB + "/inspections/" + id("real-test-b"), "GET", null).get("view").get("validity").asString());
        assertEquals("7.0.27", ok("/api/v2/data-sources/" + instanceB + "/inspections/" + id("real-test-b"), "GET", null).get("view").get("inspection").get("check").get("reportedVersion").asString());
        assertEquals(503, call("/api/v2/data-sources/" + instanceB + "/test", "POST", Map.of("requestId", id("real-test-b-blocked"), "configurationRevision", 1, "connectionDigest", digestB)).statusCode());
        var aliveA = inspection(instanceA, "test", id("real-test-a-2"), 1, digestA);
        assertEquals("CURRENT", aliveA.get("validity").asString(), aliveA.toString());
        assertTrue(aliveA.get("inspection").get("check").get("reachable").booleanValue());
    }

    @Test
    @Order(3)
    void c_realReceiptsInvalidateWhenTheConfigurationChanges() throws Exception {
        UUID instanceA = id("real-a");
        var currentA = ok("/api/v2/data-sources/" + instanceA + "/connection", "GET", null).get("connection");
        String digestA = currentA.get("connectionDigest").asString();
        assertEquals("CURRENT", ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id("real-test-a-2"), "GET", null).get("view").get("validity").asString());

        // Rotate the credential and pin the new version: the connection digest changes.
        assertEquals(200, call("/api/v2/data-sources/credentials/" + id("real-cred-a"), "PATCH",
            Map.of("requestId", id("real-rotate-a"), "expectedEditVersion", 1, "name", "OW-ST02 real credential A", "state", "ACTIVE", "secret", SECRET)).statusCode());
        var changed = ok("/api/v2/data-sources/" + instanceA + "/connection", "PATCH",
            connectionBody("real-edit-a", "OW-ST02 real instance A", endpointPin("owst02-real-a"), credentialPin("real-cred-a", 2, id("real-rotate-a")), List.of("22"), 1));
        String digestA2 = changed.get("connection").get("connectionDigest").asString();
        assertNotEquals(digestA, digestA2);
        assertEquals(2, changed.get("connection").get("revision").asInt());
        assertEquals("STALE", ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id("real-test-a-2"), "GET", null).get("view").get("validity").asString(), "the old connection receipt is no longer current");
        var after = inspection(instanceA, "test", id("real-test-a-3"), 2, digestA2);
        assertEquals("CURRENT", after.get("validity").asString(), after.toString());
        assertEquals("7.0.27", after.get("inspection").get("check").get("reportedVersion").asString());
    }

    @Test
    @Order(4)
    void d_realReadOnlyReadBackAndZeroSideEffects() throws Exception {
        UUID instanceA = id("real-a"), instanceB = id("real-b");
        var viewA = ok("/api/v2/data-sources/" + instanceA + "/connection", "GET", null);
        var viewB = ok("/api/v2/data-sources/" + instanceB + "/connection", "GET", null);
        assertEquals(2, viewA.get("connection").get("revision").asInt());
        assertEquals(1, viewB.get("connection").get("revision").asInt());
        assertEquals("AVAILABLE", viewA.get("availability").asString());
        assertEquals("UNAVAILABLE", viewB.get("availability").asString());
        assertEquals(2, ok("/api/v2/data-sources/" + instanceA + "/connection/history", "GET", null).get("items").size());
        assertEquals(1, ok("/api/v2/data-sources/" + instanceB + "/connection/history", "GET", null).get("items").size());
        for (String label : List.of("real-test-a-3")) {
            var view = ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id(label), "GET", null).get("view");
            assertTrue(Set.of("CURRENT", "EXPIRED").contains(view.get("validity").asString()), view.toString());
        }
        for (String label : List.of("real-test-a", "real-discover-a", "real-metrics-a", "real-test-a-2")) {
            var view = ok("/api/v2/data-sources/" + instanceA + "/inspections/" + id(label), "GET", null).get("view");
            assertTrue(Set.of("STALE", "EXPIRED").contains(view.get("validity").asString()), label + ": " + view.toString());
        }
        for (String label : List.of("real-test-b", "real-discover-b", "real-metrics-b")) {
            var view = ok("/api/v2/data-sources/" + instanceB + "/inspections/" + id(label), "GET", null).get("view");
            assertTrue(Set.of("STALE", "EXPIRED").contains(view.get("validity").asString()), label + ": " + view.toString());
        }
        assertEquals(0, ok("/api/v1/entities/page?limit=50", "GET", null).get("items").size());
        var runtime = ok("/api/v1/integrations/workflows/runtime", "GET", null);
        assertEquals(0, runtime.get("tasks").size());
        assertEquals(0, runtime.get("executions").size());
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))) {
            for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
                try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
                    statement.setString(1, TENANT);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1), table + " must stay empty for the real-source chain as well");
                    }
                }
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
