package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelDefinition.Cardinality;
import com.acme.opsweave.catalog.domain.ModelDefinition.Endpoints;
import com.acme.opsweave.catalog.domain.ModelDefinition.Field;
import com.acme.opsweave.catalog.domain.ModelDefinition.Kind;
import com.acme.opsweave.catalog.domain.ModelDefinition.Ref;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Edge;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Node;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Source;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Target;
import com.acme.opsweave.integration.domain.WorkflowDefinition.Type;
import com.acme.opsweave.integration.domain.WorkflowOperators;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * OW-ST06 full-chain acceptance against the real HTTP API and real PostgreSQL in loopback dev mode.
 * Every chain (builtin ingestion, custom field/entity, custom relation maintenance) writes through
 * controlled platform APIs and reads back through the same APIs. The {@code d_*} method is strictly
 * read-only (GET and SELECT only) so a second JVM run of just {@code d_*} proves the rows survive a
 * platform process restart. Fixture origins keep their fixture labels and never claim live status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=owst06-acceptance-fixture-only-32chars",
    "opsweave.auth.dev.subject=owst06-author",
    "opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=owst06-fixture",
    "opsweave.inventory.store=postgres"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresOwSt06AcceptanceIT {
    static final String TOKEN = "owst06-acceptance-fixture-only-32chars";
    static final String SEED = System.getenv("OPSWEAVE_OW_ST06_SEED") == null ? UUID.randomUUID().toString() : System.getenv("OPSWEAVE_OW_ST06_SEED");
    static final String TENANT = "ow-st06-" + SEED;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("opsweave.auth.dev.tenant", () -> TENANT);
        r.add("opsweave.inventory.jdbc-url", () -> System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        r.add("opsweave.inventory.jdbc-user", () -> System.getenv("OPSWEAVE_TEST_JDBC_USER"));
        r.add("opsweave.inventory.jdbc-password", () -> System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    @LocalServerPort int port;
    @Autowired InventoryWiring wiring;
    @Autowired IngestZabbixHostsUseCase hosts;
    final HttpClient http = HttpClient.newHttpClient();

    static UUID id(String label) { return UUID.nameUUIDFromBytes((TENANT + "|" + label).getBytes(StandardCharsets.UTF_8)); }

    HttpResponse<String> call(String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (body == null) b.GET(); else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> call(String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body == null) b.GET(); else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String path, String body) throws Exception {
        var response = call(path, body, TOKEN);
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return CatalogJson.JSON.readTree(response.body());
    }

    static String json(Object value) { return CatalogJson.JSON.writeValueAsString(value); }

    // ---------- shared deterministic definitions ----------

    static ModelDefinition bizModel() {
        return new ModelDefinition("custom.owst06_biz", 1, Kind.ENTITY, "OW-ST06 business entity", "OW-ST06 acceptance",
            List.of(new Field("biz_name", "Business name", ModelDefinition.Type.TEXT, true, 64, null, null, List.of()),
                new Field("biz_code", "Business code", ModelDefinition.Type.TEXT, false, 32, null, null, List.of())), null);
    }

    static Field extensionField() { return new Field("owner_team", "Owner team", ModelDefinition.Type.TEXT, false, 32, null, null, List.of()); }

    static ModelDefinition linkModel() {
        return new ModelDefinition("custom.owst06_link", 1, Kind.RELATION, "OW-ST06 link", "OW-ST06 acceptance", List.of(),
            new Endpoints(new Ref("builtin.host", 1), new Ref("builtin.application", 1), Cardinality.MANY_TO_MANY));
    }

    static WorkflowDefinition builtinGraph() {
        var model = new BuiltinCatalog().definitions().stream().filter(m -> m.id().equals("builtin.host")).findFirst().orElseThrow();
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition("owst06_builtin_out", 1, "OW-ST06 builtin ingestion",
            new Source("ZABBIX_HOST", "owst06-fixture"), new Target(model.id(), 1, model.digest()),
            List.of(new Node("source", Type.SOURCE, "1", Map.of()), new Node("map", Type.MAP, "1", Map.of("name", "hostname", "ip", "ip")),
                new Node("validate", Type.VALIDATE, "1", Map.of()), new Node("output", Type.OUTPUT, "1", Map.of())),
            List.of(new Edge("source", "map"), new Edge("map", "validate"), new Edge("validate", "output"))));
    }

    static WorkflowDefinition relationGraph() {
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition("owst06_rel_out", 1, "OW-ST06 relation output",
            new Source("MANUAL_SAMPLE", "manual"), new Target("custom.owst06_link", 1, linkModel().digest(), "RELATION", null, null, "from_ref", "to_ref"),
            List.of(new Node("source", Type.SOURCE, "1", Map.of()), new Node("map", Type.MAP, "1", Map.of("raw_from", "from_ref", "raw_to", "to_ref")),
                new Node("validate", Type.VALIDATE, "1", Map.of()), new Node("output", Type.OUTPUT, "1", Map.of())),
            List.of(new Edge("source", "map"), new Edge("map", "validate"), new Edge("validate", "output"))));
    }

    Map<String, Object> workflowInput(WorkflowDefinition d, int edit, List<Map<String, Object>> samples, UUID syncRunId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", d.id()); body.put("revision", 1); body.put("digest", d.digest()); body.put("editVersion", edit); body.put("dryRun", true);
        if (samples != null) body.put("samples", samples); else body.put("syncRunId", syncRunId);
        return body;
    }

    void publishWorkflow(WorkflowDefinition d, List<Map<String, Object>> samples, UUID syncRunId) throws Exception {
        var layout = new LinkedHashMap<String, Object>();
        for (int i = 0; i < d.nodes().size(); i++) layout.put(d.nodes().get(i).id(), Map.of("x", 100, "y", i * 100));
        ok("/api/v1/integrations/workflows/drafts", json(Map.of("definition", WorkflowJson.wire(d), "layout", layout, "expectedEditVersion", 0)));
        var preview = ok("/api/v1/integrations/workflows/preview", json(workflowInput(d, 1, samples, syncRunId)));
        ok("/api/v1/integrations/workflows/publish", json(Map.of("id", d.id(), "revision", 1, "digest", d.digest(), "editVersion", 1, "previewId", preview.get("receipt").get("id").asString())));
    }

    Map<String, Object> executeBody(WorkflowDefinition d, Map<String, Object> settings, List<Map<String, Object>> samples, UUID syncRunId) throws Exception {
        var run = ok("/api/v1/integrations/workflows/run", json(workflowInput(d, 0, samples, syncRunId)));
        var body = new LinkedHashMap<String, Object>();
        body.put("id", d.id()); body.put("revision", 1); body.put("digest", d.digest()); body.put("settings", settings);
        body.put("previewId", run.get("receipt").get("id").asString());
        if (samples != null) body.put("samples", samples); else body.put("syncRunId", syncRunId);
        return body;
    }

    JsonNode execute(WorkflowDefinition d, Map<String, Object> settings, List<Map<String, Object>> samples, UUID syncRunId) throws Exception {
        return ok("/api/v1/integrations/workflows/runtime/execute", json(executeBody(d, settings, samples, syncRunId)));
    }

    String entityBody(UUID requestId, UUID entityId, String modelId, String name, Map<String, Object> attributes, Long expectedVersion) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", requestId.toString()); body.put("entityId", entityId.toString());
        body.put("model", Map.of("id", modelId, "revision", 1)); body.put("name", name); body.put("lifecycle", "ACTIVE");
        body.put("attributes", attributes);
        if (expectedVersion != null) body.put("expectedVersion", expectedVersion);
        return json(body);
    }

    Map<String, Object> attributes(String... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    // ---------- a. builtin model ingestion chain ----------

    @Test
    @Order(1)
    void a_builtinIngestionChainWritesEntitiesReadableThroughApi() throws Exception {
        var sync = hosts.execute(principal(), "owst06-fixture");
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, sync.kind());
        var d = builtinGraph();
        publishWorkflow(d, null, sync.syncRunId());
        var body = executeBody(d, Map.of("identityField", "entity_id", "nameField", "hostname"), null, sync.syncRunId());
        var first = ok("/api/v1/integrations/workflows/runtime/execute", json(body));
        assertEquals("SUCCEEDED", first.get("state").asString(), first.toString());
        assertEquals("fixture", first.get("origin").asString());
        assertEquals(2, first.get("accepted").asInt());
        assertEquals(2, first.get("entityIds").size());
        assertEquals(0, first.get("pending").size());
        for (var entityId : first.get("entityIds")) {
            var entity = ok("/api/v1/entities/" + entityId.asString(), null);
            assertEquals("Host", entity.get("entityType").asString());
            assertEquals("workflow", entity.get("attributes").get("source").asString());
            assertEquals("labeled-fixture", entity.get("attributes").get("dataMode").asString());
            assertEquals("workflow.owst06_builtin_out", entity.get("attributes").get("sourceInstanceId").asString());
            assertEquals(d.digest(), entity.get("attributes").get("pipelineDigest").asString());
            assertFalse(entity.toString().contains("zabbix-jsonrpc"), "fixture rows must not claim live source mode");
        }
        var page = ok("/api/v1/entities/page?limit=50", null);
        var seen = new ArrayList<String>();
        for (var item : page.get("items")) seen.add(item.get("id").asString());
        for (var entityId : first.get("entityIds")) assertTrue(seen.contains(entityId.asString()), entityId + " missing from paged read-back");
        // Idempotent batch execution: the same command identity replays the same outcomes.
        var second = ok("/api/v1/integrations/workflows/runtime/execute", json(body));
        assertEquals(first, second);
    }

    // ---------- b. custom field / entity chain ----------

    @Test
    @Order(2)
    void b_customModelExtensionEntityChainWritesReadsThroughApi() throws Exception {
        var biz = bizModel();
        var draft = ok("/api/v1/catalog/drafts", json(Map.of("definition", CatalogJson.wire(biz), "expectedEditVersion", 0)));
        assertEquals(1, draft.get("editVersion").asInt());
        assertEquals(biz.digest(), draft.get("digest").asString());
        assertEquals(409, call("/api/v1/catalog/drafts", json(Map.of("definition", CatalogJson.wire(biz), "expectedEditVersion", 0)), TOKEN).statusCode());
        var published = ok("/api/v1/catalog/publish", json(Map.of("ref", Map.of("id", biz.id(), "revision", 1), "expectedEditVersion", 1, "digest", biz.digest())));
        assertEquals("PUBLISHED", published.get("state").asString());
        assertEquals(published, ok("/api/v1/catalog/versions/" + biz.id() + "/1", null));
        assertEquals(400, call("/api/v1/catalog/drafts", json(Map.of("definition", CatalogJson.wire(new ModelDefinition("builtin.host", 1, Kind.ENTITY, "Overwrite attempt", "OW-ST06 negative", List.of(), null)), "expectedEditVersion", 0)), TOKEN).statusCode());

        var extension = ok("/api/v1/catalog/extensions/drafts", json(Map.of("base", Map.of("id", biz.id(), "revision", 1), "fields", List.of(CatalogJson.wireField(extensionField())), "expectedEditVersion", 0)));
        assertEquals(1, extension.get("extension").get("revision").asInt());
        String extensionDigest = extension.get("extension").get("digest").asString();
        assertEquals(400, call("/api/v1/catalog/extensions/drafts", json(Map.of("base", Map.of("id", biz.id(), "revision", 1), "fields", List.of(CatalogJson.wireField(new Field("owner_team", "Owner team", ModelDefinition.Type.TEXT, true, 32, null, null, List.of()))), "expectedEditVersion", 1)), TOKEN).statusCode());
        assertEquals(409, call("/api/v1/catalog/extensions/drafts", json(Map.of("base", Map.of("id", biz.id(), "revision", 1), "fields", List.of(CatalogJson.wireField(new Field("biz_name", "Collision", ModelDefinition.Type.TEXT, false, 32, null, null, List.of()))), "expectedEditVersion", 1)), TOKEN).statusCode());
        var extensionPublished = ok("/api/v1/catalog/extensions/publish", json(Map.of("base", Map.of("id", biz.id(), "revision", 1), "expectedEditVersion", 1, "digest", extensionDigest)));
        assertEquals("PUBLISHED", extensionPublished.get("state").asString());

        var entityId = id("biz-entity");
        var receipt = ok("/api/v1/entities", entityBody(id("biz-entity-write"), entityId, biz.id(), "OW-ST06 business", attributes("biz_name", "owst06-biz", "biz_code", "B-001", "owner_team", "platform"), null));
        assertTrue(receipt.get("replayed").isBoolean());
        assertEquals(biz.digest(), receipt.get("model").get("digest").asString());
        var detail = ok("/api/v1/entities/" + entityId, null);
        assertEquals("custom.owst06_biz", detail.get("entityType").asString());
        assertEquals("owst06-biz", detail.get("attributes").get("biz_name").asString());
        assertEquals("platform", detail.get("attributes").get("owner_team").asString());
        assertEquals(biz.digest(), detail.get("model").get("digest").asString());
        var list = ok("/api/v1/entities/page?limit=50", null);
        var seen = new ArrayList<String>();
        for (var item : list.get("items")) seen.add(item.get("id").asString());
        assertTrue(seen.contains(entityId.toString()));

        assertEquals(400, call("/api/v1/entities", entityBody(id("biz-bad-field"), id("biz-bad-field"), biz.id(), "Bad", attributes("biz_name", "x", "unknown_field", "y"), null), TOKEN).statusCode());
        assertEquals(400, call("/api/v1/entities", entityBody(id("biz-missing"), id("biz-missing"), biz.id(), "Bad", attributes("biz_code", "B-002"), null), TOKEN).statusCode());
        assertEquals(409, call("/api/v1/entities", entityBody(id("biz-unpublished"), id("biz-unpublished"), "custom.owst06_missing", "Bad", attributes("biz_name", "x"), null), TOKEN).statusCode());
        assertEquals(409, call("/api/v1/entities", entityBody(id("biz-wrong-kind"), id("biz-wrong-kind"), "custom.owst06_link", "Bad", Map.of(), null), TOKEN).statusCode());
        assertEquals(409, call("/api/v1/entities", entityBody(id("biz-entity-write"), id("biz-entity-2"), biz.id(), "Reused request", attributes("biz_name", "other"), null), TOKEN).statusCode());
        assertEquals(409, call("/api/v1/entities", entityBody(id("biz-cas"), entityId, biz.id(), "OW-ST06 business", attributes("biz_name", "owst06-biz", "biz_code", "B-001", "owner_team", "platform"), 7L), TOKEN).statusCode());
        var updated = ok("/api/v1/entities", entityBody(id("biz-entity-update"), entityId, biz.id(), "OW-ST06 business", attributes("biz_name", "owst06-biz", "biz_code", "B-001", "owner_team", "platform"), 1L));
        assertEquals(2, updated.get("entity").get("version").asInt());
    }

    // ---------- c. custom relation maintenance chain ----------

    @Test
    @Order(3)
    void c_customRelationMaintenanceChainCoversLifecycleCorrectionsConfirmationsAndOutputs() throws Exception {
        var link = linkModel();
        var draft = ok("/api/v1/catalog/drafts", json(Map.of("definition", CatalogJson.wire(link), "expectedEditVersion", 0)));
        assertEquals(link.digest(), draft.get("digest").asString());
        ok("/api/v1/catalog/publish", json(Map.of("ref", Map.of("id", link.id(), "revision", 1), "expectedEditVersion", 1, "digest", link.digest())));

        var hostA = id("rel-host-a"); var appB = id("rel-app-b"); var hostC = id("rel-host-c");
        var hostD = id("rel-host-d"); var appE = id("rel-app-e"); var appF = id("rel-app-f");
        ok("/api/v1/entities", entityBody(id("rel-host-a-write"), hostA, "builtin.host", "OW-ST06 host A", attributes("hostname", "owst06-host-a"), null));
        ok("/api/v1/entities", entityBody(id("rel-app-b-write"), appB, "builtin.application", "OW-ST06 app B", attributes("name", "owst06-app-b"), null));
        ok("/api/v1/entities", entityBody(id("rel-host-c-write"), hostC, "builtin.host", "OW-ST06 host C", attributes("hostname", "owst06-host-c"), null));
        ok("/api/v1/entities", entityBody(id("rel-host-d-write"), hostD, "builtin.host", "OW-ST06 host D", attributes("hostname", "owst06-host-d"), null));
        ok("/api/v1/entities", entityBody(id("rel-app-e-write"), appE, "builtin.application", "OW-ST06 app E", attributes("name", "owst06-app-e"), null));
        ok("/api/v1/entities", entityBody(id("rel-app-f-write"), appF, "builtin.application", "OW-ST06 app F", attributes("name", "owst06-app-f"), null));

        var create = new LinkedHashMap<String, Object>();
        create.put("requestId", id("rel-create-ab").toString()); create.put("relationType", "custom.owst06_link"); create.put("relationRevision", 1);
        create.put("fromEntityId", hostA.toString()); create.put("toEntityId", appB.toString());
        create.put("validFrom", java.time.Instant.parse("2026-10-01T00:00:00Z").toString());
        create.put("sourceRef", "operator"); create.put("dataMode", "import");
        create.put("expectedFromVersion", 1); create.put("expectedToVersion", 1);
        var created = ok("/api/v1/entities/" + hostA + "/relations", json(create));
        String relationAB = created.get("relation").get("id").asString();
        assertEquals(id("rel-create-ab").toString(), relationAB);
        assertEquals("import", created.get("relation").get("dataMode").asString());
        var duplicate = new LinkedHashMap<String, Object>(create);
        duplicate.put("requestId", id("rel-create-ab-duplicate").toString());
        assertEquals(409, call("/api/v1/entities/" + hostA + "/relations", json(duplicate), TOKEN).statusCode());

        var confirmFixture = ok("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/confirmations",
            json(Map.of("requestId", id("rel-confirm-fixture").toString(), "sourceKind", "source", "sourceKey", "owst06-fixture-1", "batchRef", "owst06-batch-1", "dataMode", "fixture")));
        assertEquals("CONFIRMED", confirmFixture.get("entry").get("state").asString());
        var confirmImport = ok("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/confirmations",
            json(Map.of("requestId", id("rel-confirm-import").toString(), "sourceKind", "manual", "sourceKey", "owst06-import-1", "batchRef", "owst06-batch-2", "dataMode", "import")));
        assertEquals("CONFIRMED", confirmImport.get("entry").get("state").asString());
        ok("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/confirmations/withdraw",
            json(Map.of("requestId", id("rel-withdraw-fixture").toString(), "sourceKind", "source", "sourceKey", "owst06-fixture-1", "batchRef", "owst06-batch-1", "reason", "fixture source expired")));
        var confirmations = ok("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/confirmations", null);
        assertEquals("ACTIVE", confirmations.get("relationState").asString());
        assertEquals(2, confirmations.get("entries").size());
        var entryStates = new LinkedHashMap<String, String>();
        var entryModes = new LinkedHashMap<String, String>();
        for (var entry : confirmations.get("entries")) {
            entryStates.put(entry.get("sourceKey").asString(), entry.get("state").asString());
            entryModes.put(entry.get("sourceKey").asString(), entry.get("dataMode").asString());
        }
        assertEquals("WITHDRAWN", entryStates.get("owst06-fixture-1"));
        assertEquals("CONFIRMED", entryStates.get("owst06-import-1"));
        assertEquals("fixture", entryModes.get("owst06-fixture-1"));
        assertEquals("import", entryModes.get("owst06-import-1"));

        var correction = ok("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/correct", json(Map.of(
            "requestId", id("rel-correction").toString(), "relationId", relationAB, "reason", "wrong target application",
            "expectedVersion", 1, "fromEntityId", hostA.toString(), "toEntityId", appE.toString(), "relationType", "custom.owst06_link", "relationRevision", 1)));
        assertEquals(relationAB, correction.get("previousRelationId").asString());
        String relationAE = correction.get("relation").get("id").asString();
        assertEquals("import", correction.get("relation").get("dataMode").asString());
        var corrections = ok("/api/v1/entities/" + hostA + "/relations/corrections", null);
        boolean correctionRecorded = false;
        for (var record : corrections.get("items")) {
            if (record.get("requestId").asString().equals(id("rel-correction").toString())) {
                correctionRecorded = true;
                assertEquals(relationAB, record.get("previousRelationId").asString());
                assertEquals(relationAE, record.get("relationId").asString());
                assertEquals("wrong target application", record.get("reason").asString());
            }
        }
        assertTrue(correctionRecorded);
        var history = ok("/api/v1/entities/" + hostA + "/relations/history", null);
        boolean originalEnded = false;
        for (var item : history.get("items")) {
            var relation = item.get("relation");
            if (relation.get("id").asString().equals(relationAB)) {
                originalEnded = relation.get("validTo").asString() != null;
            }
        }
        assertTrue(originalEnded, "corrected original keeps its history with an effective end time");
        assertEquals(409, call("/api/v1/entities/" + hostA + "/relations/" + relationAB + "/correct", json(Map.of(
            "requestId", id("rel-correction-again").toString(), "relationId", relationAB, "reason", "no revival",
            "expectedVersion", 1, "fromEntityId", hostA.toString(), "toEntityId", appE.toString(), "relationType", "custom.owst06_link", "relationRevision", 1)), TOKEN).statusCode());

        var closed = ok("/api/v1/entities/" + hostA + "/relations/" + relationAE + "/close", json(Map.of(
            "requestId", id("rel-close-ae").toString(), "relationId", relationAE, "reason", "decommissioned", "expectedVersion", 1)));
        assertNotNull(closed.get("relation").get("validTo").asString());
        assertEquals("TERMINATED", ok("/api/v1/entities/" + hostA + "/relations/" + relationAE + "/confirmations", null).get("relationState").asString());
        assertEquals(409, call("/api/v1/entities/" + hostA + "/relations/" + relationAE + "/close", json(Map.of(
            "requestId", id("rel-close-ae-again").toString(), "relationId", relationAE, "reason", "twice", "expectedVersion", 2)), TOKEN).statusCode());
        var revive = new LinkedHashMap<String, Object>(create);
        revive.put("requestId", id("rel-revive-ab").toString());
        assertEquals(409, call("/api/v1/entities/" + hostA + "/relations", json(revive), TOKEN).statusCode());

        var d = relationGraph();
        publishWorkflow(d, List.of(Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-1"), Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-2"), Map.of("raw_from", "ext-from-9", "raw_to", "ext-to-1")), null);
        ok("/api/v1/external-links", json(Map.of("requestId", id("link-from-1").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-from-1", "generation", "builtin.host", "targetId", hostD.toString(), "reason", "OW-ST06 acceptance")));
        ok("/api/v1/external-links", json(Map.of("requestId", id("link-to-1").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-to-1", "generation", "builtin.application", "targetId", appE.toString(), "reason", "OW-ST06 acceptance")));
        var settings = Map.<String, Object>of("identityField", "raw_from", "nameField", "from_ref");
        List<Map<String, Object>> samples = List.of(Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-1"), Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-2"), Map.of("raw_from", "ext-from-9", "raw_to", "ext-to-1"));
        var execution = execute(d, settings, samples, null);
        assertEquals("SUCCEEDED", execution.get("state").asString(), execution.toString());
        assertEquals("MANUAL_SAMPLE", execution.get("origin").asString());
        assertEquals(3, execution.get("accepted").asInt());
        assertEquals(1, execution.get("relationIds").size());
        assertEquals(2, execution.get("pending").size());
        assertEquals(3, execution.get("relationIds").size() + execution.get("pending").size() + execution.get("entityIds").size());
        String relationDE = execution.get("relationIds").get(0).asString();
        var pendingCodes = new LinkedHashMap<Integer, String>();
        for (var pending : execution.get("pending")) pendingCodes.put(pending.get("index").asInt(), pending.get("code").asString());
        assertEquals("TO_UNRESOLVED", pendingCodes.get(1));
        assertEquals("FROM_UNRESOLVED", pendingCodes.get(2));
        var receipt = ok("/api/v1/integrations/workflows/runtime/executions/" + execution.get("id").asString(), null);
        assertEquals(execution, receipt);

        ok("/api/v1/external-links", json(Map.of("requestId", id("link-to-2").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-to-2", "generation", "builtin.application", "targetId", appF.toString(), "reason", "OW-ST06 acceptance")));
        var resolved = execute(d, settings, List.of(Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-2")), null);
        assertEquals("SUCCEEDED", resolved.get("state").asString(), resolved.toString());
        assertEquals(1, resolved.get("accepted").asInt());
        assertEquals(1, resolved.get("relationIds").size());
        assertNotEquals(relationDE, resolved.get("relationIds").get(0).asString());
        assertEquals(0, resolved.get("pending").size());
        // A row that already wrote a relation fails closed on a new command identity: never a silent win.
        var duplicateRow = execute(d, settings, List.of(Map.of("raw_from", "ext-from-1", "raw_to", "ext-to-1")), null);
        assertEquals("FAILED", duplicateRow.get("state").asString(), duplicateRow.toString());
        assertEquals("OUTPUT_UNAVAILABLE", duplicateRow.get("error").asString());
        assertEquals(0, duplicateRow.get("relationIds").size());

        var relations = ok("/api/v1/entities/" + hostD + "/relations", null);
        boolean found = false;
        for (var relation : relations.get("items")) {
            if (relation.get("id").asString().equals(relationDE)) {
                found = true;
                assertEquals("unknown", relation.get("dataMode").asString());
            }
        }
        assertTrue(found, "RELATION output row is readable through the relation API");
        var written = wiring.relations().find(new TenantId(TENANT), UUID.fromString(relationDE)).orElseThrow();
        assertEquals("workflow:workflow.owst06_rel_out", written.sourceRef());
        assertEquals("unknown", written.dataMode());
        assertEquals(409, call("/api/v1/external-links", json(Map.of("requestId", id("link-conflict").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-from-1", "generation", "builtin.host", "targetId", hostC.toString(), "reason", "conflict")), TOKEN).statusCode());
        var replay = ok("/api/v1/external-links", json(Map.of("requestId", id("link-from-1").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-from-1", "generation", "builtin.host", "targetId", hostD.toString(), "reason", "OW-ST06 acceptance")));
        assertTrue(replay.get("replayed").asBoolean());
    }

    // ---------- e. failure and overreach boundaries ----------

    @Test
    @Order(4)
    void e_failureAndOverreachBoundariesAreExplicit() throws Exception {
        assertEquals(401, call("/api/v1/entities", null, null).statusCode());
        assertEquals(400, call("/api/v1/entities/" + id("rel-host-a") + "/relations?tenantId=forged", null, TOKEN).statusCode());
        assertEquals(404, call("/api/v1/entities/" + UUID.randomUUID(), null, TOKEN).statusCode());
        assertEquals(404, call("/api/v1/entities/" + id("rel-host-a") + "/relations/" + UUID.randomUUID() + "/confirmations", null, TOKEN).statusCode());
        assertEquals(404, call("/api/v1/integrations/workflows/runtime/executions/" + UUID.randomUUID(), null, TOKEN).statusCode());
        assertEquals(409, call("/api/v1/entities/" + id("rel-host-a") + "/relations", json(Map.of(
            "requestId", id("rel-missing-endpoint").toString(), "relationType", "custom.owst06_link", "relationRevision", 1,
            "fromEntityId", id("rel-host-a").toString(), "toEntityId", UUID.randomUUID().toString(),
            "validFrom", java.time.Instant.parse("2026-10-01T00:00:00Z").toString(),
            "expectedFromVersion", 1, "expectedToVersion", 1)), TOKEN).statusCode());
        assertEquals(404, call("/api/v1/external-links", json(Map.of("requestId", id("link-missing-target").toString(), "namespace", "workflow.owst06_rel_out", "objectType", "entity", "externalKey", "ext-missing", "targetId", UUID.randomUUID().toString())), TOKEN).statusCode());
        assertEquals(400, call("/api/v1/entities/" + id("rel-host-a") + "/relations?limit=999", null, TOKEN).statusCode());
        assertEquals(409, call("/api/v1/catalog/publish", json(Map.of("ref", Map.of("id", "custom.owst06_biz", "revision", 1), "expectedEditVersion", 9, "digest", bizModel().digest())), TOKEN).statusCode());
        // Tenant isolation: rows are invisible to any other tenant on the same real connections.
        assertTrue(wiring.query().find(new TenantId(TENANT + "-other"), new EntityId(id("rel-host-a"))).isEmpty());
    }

    // ---------- d. restart / new-connection read-back (strictly read-only) ----------

    @Test
    @Order(5)
    void d_restartedProcessReadsBackConsistently() throws Exception {
        var biz = bizModel();
        var bizEntity = ok("/api/v1/entities/" + id("biz-entity"), null);
        assertEquals("custom.owst06_biz", bizEntity.get("entityType").asString());
        assertEquals("owst06-biz", bizEntity.get("attributes").get("biz_name").asString());
        assertEquals("platform", bizEntity.get("attributes").get("owner_team").asString());
        assertEquals(biz.digest(), bizEntity.get("model").get("digest").asString());
        var versions = ok("/api/v1/catalog/versions/" + biz.id() + "/1", null);
        assertEquals(biz.digest(), versions.get("digest").asString());
        var extensions = ok("/api/v1/catalog/extensions", null);
        boolean extensionFound = false;
        for (var item : extensions.get("published").get("items")) {
            var extension = item.get("extension");
            if (extension.get("base").get("id").asString().equals(biz.id())) {
                extensionFound = true;
                assertEquals(1, extension.get("revision").asInt());
                assertEquals("owner_team", extension.get("fields").get(0).get("id").asString());
            }
        }
        assertTrue(extensionFound);

        var link = linkModel();
        var versionsLink = ok("/api/v1/catalog/versions/" + link.id() + "/1", null);
        assertEquals(link.digest(), versionsLink.get("digest").asString());
        var hostA = ok("/api/v1/entities/" + id("rel-host-a"), null);
        assertEquals("owst06-host-a", hostA.get("attributes").get("hostname").asString());
        var catalog = ok("/api/v1/catalog", null);
        for (var definition : catalog.get("package").get("definitions")) {
            var builtin = new BuiltinCatalog().definitions().stream().filter(m -> m.id().equals(definition.get("id").asString())).findFirst().orElseThrow();
            assertEquals(builtin.digest(), CatalogJson.definition(definition).digest(), "builtin model bodies and digests stay untouched");
        }
        var relationPage = ok("/api/v1/entities/" + id("rel-host-d") + "/relations", null);
        boolean outputRelationVisible = false;
        for (var relation : relationPage.get("items")) {
            if (relation.get("relationType").asString().equals("custom.owst06_link")) {
                outputRelationVisible = true;
                assertEquals("unknown", relation.get("dataMode").asString());
            }
        }
        assertTrue(outputRelationVisible);
        var history = ok("/api/v1/entities/" + id("rel-host-a") + "/relations/history", null);
        assertTrue(history.get("items").size() >= 2);
        var corrections = ok("/api/v1/entities/" + id("rel-host-a") + "/relations/corrections", null);
        boolean correctionRecorded = false;
        for (var record : corrections.get("items")) {
            if (record.get("requestId").asString().equals(id("rel-correction").toString())) correctionRecorded = true;
        }
        assertTrue(correctionRecorded);
        var links = ok("/api/v1/external-links?namespace=workflow.owst06_rel_out&limit=50", null);
        var linkTargets = new ArrayList<String>();
        for (var item : links.get("items")) linkTargets.add(item.get("link").get("targetId").asString());
        assertTrue(linkTargets.contains(id("rel-host-d").toString()));
        assertTrue(linkTargets.contains(id("rel-app-e").toString()));
        assertTrue(linkTargets.contains(id("rel-app-f").toString()));
        var runtime = ok("/api/v1/integrations/workflows/runtime", null);
        boolean relationExecutionFound = false;
        for (var execution : runtime.get("executions")) {
            if (execution.get("workflowId").asString().equals("owst06_rel_out")) {
                if (execution.get("state").asString().equals("SUCCEEDED") && execution.get("pending").size() > 0) relationExecutionFound = true;
                if (execution.get("state").asString().equals("SUCCEEDED")) {
                    assertEquals(execution.get("accepted").asInt(), execution.get("relationIds").size() + execution.get("pending").size() + execution.get("entityIds").size());
                }
            }
        }
        assertTrue(relationExecutionFound, "relation output receipts survive the restart with pending rows intact");

        // Rebuild the inventory wiring: fresh data source, fresh pools, fresh connections.
        var reopened = InventoryWiring.open(new OpsweaveProperties(
            new OpsweaveProperties.Auth("dev", true, new OpsweaveProperties.Auth.Dev(TOKEN, "owst06-author", TENANT, "entity.read,entity.manage,source.sync", "")),
            new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "owst06-fixture", 1),
            new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))), "");
        try {
            var tenant = new TenantId(TENANT);
            var readA = reopened.query().find(tenant, new EntityId(id("rel-host-a"))).orElseThrow();
            assertEquals("owst06-host-a", readA.attributes().get("hostname"));
            var readBiz = reopened.query().find(tenant, new EntityId(id("biz-entity"))).orElseThrow();
            assertEquals("platform", readBiz.attributes().get("owner_team"));
            assertEquals(biz.digest(), readBiz.model().digest());
            var readRelations = reopened.relations().history(tenant, new EntityId(id("rel-host-a")), null, 25);
            assertTrue(readRelations.size() >= 2);
            var readOutput = reopened.relations().history(tenant, new EntityId(id("rel-host-d")), null, 25);
            boolean sourceKept = false;
            for (var relation : readOutput) {
                if (relation.relationType().equals("custom.owst06_link")) sourceKept = "workflow:workflow.owst06_rel_out".equals(relation.sourceRef()) && "unknown".equals(relation.dataMode());
            }
            assertTrue(sourceKept, "relation source reference and data mode survive the restart");
            var readLinks = reopened.externalLinkRegistrations().page(tenant, "workflow.owst06_rel_out", null, 25);
            assertEquals(3, readLinks.size());
            assertTrue(reopened.modelCatalog().find(tenant, new Ref(biz.id(), 1)).isPresent());
            assertTrue(reopened.modelExtensions().latest(tenant, new Ref(biz.id(), 1)).isPresent());
            assertTrue(reopened.query().find(new TenantId(TENANT + "-other"), new EntityId(id("rel-host-a"))).isEmpty());
        } finally {
            reopened.close();
        }

        // No diagnosis and no model call happened across the whole chain.
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))) {
            for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
                try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
                    statement.setString(1, TENANT);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1), table + " must stay empty: the acceptance chain never triggers AI diagnosis");
                    }
                }
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    Principal principal() {
        return new Principal(new com.acme.opsweave.identity.domain.SubjectId("owst06-author"), new TenantId(TENANT),
            Set.of(Permission.ENTITY_READ, Permission.ENTITY_MANAGE, Permission.SOURCE_SYNC), ResourceScope.tenantWide());
    }
}
