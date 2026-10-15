package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.SkillContent;
import com.acme.opsweave.aicontrol.infrastructure.InMemorySkillReleaseStore;
import com.acme.opsweave.identity.domain.Authorizer;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP adapter regression tests; the explicit in-memory fixture never invokes a model or a tool. */
class SkillControllerTest {
    private static final TenantId TENANT = new TenantId("skill-http-fixture");
    private static final String SKILL = "fixture.diagnosis";
    private final AtomicReference<Principal> principal = new AtomicReference<>();
    private MockMvc http;

    @BeforeEach
    void fixture() {
        var wiring = mock(InventoryWiring.class);
        when(wiring.skillReleases()).thenReturn(new InMemorySkillReleaseStore());
        principal.set(user("author"));
        var controller = new SkillController(principal::get, new Authorizer()::decide, wiring);
        http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ToolErrors()).build();
    }

    @Test
    void versionPagesTrimTheSentinelAndResumeAfterTheLastReturnedVersion() throws Exception {
        for (int i = 1; i <= 4; i++) publish("1.0." + i);
        var first = get("/" + SKILL + "/versions?limit=2", 200);
        assertEquals(2, first.get("items").size());
        assertTrue(first.get("hasMore").asBoolean());
        assertEquals(first.get("items").get(1).get("version"), first.get("nextCursor"));
        var second = get("/" + SKILL + "/versions?limit=2&after=" + first.get("nextCursor").asString(), 200);
        assertEquals(2, second.get("items").size());
        assertFalse(second.get("hasMore").asBoolean());
        assertTrue(second.get("nextCursor").isNull());
        var versions = new ArrayList<String>();
        for (var page : List.of(first, second)) for (var item : page.get("items")) versions.add(item.get("version").asString());
        assertEquals(Set.of("1.0.1", "1.0.2", "1.0.3", "1.0.4"), Set.copyOf(versions));
        assertEquals(4, versions.size());
        assertEquals("1.0.4", get("/" + SKILL + "/release", 200).get("currentVersion").asString());
    }

    @Test
    void commandsReplayTheirReceiptsAndConflictErrorsRemainStructured() throws Exception {
        var request = saveBody("1.0.1", 1);
        var first = post("/drafts", request, 200);
        assertEquals(first, post("/drafts", request, 200));
        var changed = new LinkedHashMap<>(request);
        changed.put("version", "1.0.2");
        error(post("/drafts", changed, 409), "SKILL_REQUEST_REUSED");
        error(post("/drafts", saveBody("1.0.2", 2), 409), "SKILL_STATE_CONFLICT");
        var reserved = saveBody("1.0.0", 1);
        reserved.put("skillId", "incident.diagnose");
        error(post("/drafts", reserved, 409), "SKILL_COORDINATE_RESERVED");
        assertEquals(1, get("/drafts", 200).get("items").size());
    }

    @Test
    void draftPagesReturnOnlyTheRequestedRowsAndDoNotSkipTheirSentinel() throws Exception {
        for (int i = 1; i <= 3; i++) post("/drafts", saveBody("1.0." + i, 1), 200);
        var first = get("/drafts?limit=2", 200);
        assertEquals(2, first.get("items").size());
        assertTrue(first.get("hasMore").asBoolean());
        assertEquals(first.get("items").get(1).get("draftId"), first.get("nextCursor"));
        var last = get("/drafts?limit=2&after=" + first.get("nextCursor").asString(), 200);
        assertEquals(1, last.get("items").size());
        assertFalse(last.get("hasMore").asBoolean());
        assertTrue(last.get("nextCursor").isNull());
        assertNotEquals(first.get("items").get(0).get("draftId"), last.get("items").get(0).get("draftId"));
        assertNotEquals(first.get("items").get(1).get("draftId"), last.get("items").get(0).get("draftId"));
    }

    @Test
    void publicationRequiresAnotherAuthorizedReviewerAndFailedWritesPreserveThePointer() throws Exception {
        publish("1.0.1");
        principal.set(user("author"));
        var draft = post("/drafts", saveBody("1.0.2", 1), 200).get("draftId").asString();
        post("/drafts/" + draft + "/reviews", command(), 200);
        var publication = command();
        error(post("/drafts/" + draft + "/publications", publication, 403), "FORBIDDEN");
        assertEquals("1.0.1", get("/" + SKILL + "/release", 200).get("currentVersion").asString());
        principal.set(user("reviewer"));
        var published = post("/drafts/" + draft + "/publications", publication, 200);
        assertEquals(published, post("/drafts/" + draft + "/publications", publication, 200));
        error(post("/drafts", saveBody("1.0.2", 1), 409), "SKILL_VERSION_IMMUTABLE");
        var rollback = command();
        rollback.put("toVersion", "1.0.1");
        rollback.put("reason", "Fixture rollback check");
        var receipt = post("/" + SKILL + "/rollbacks", rollback, 200);
        assertEquals(receipt, post("/" + SKILL + "/rollbacks", rollback, 200));
        assertEquals("1.0.1", get("/" + SKILL + "/release", 200).get("currentVersion").asString());
        assertEquals(published.get("contentDigest"), get("/" + SKILL + "/versions/1.0.2", 200).get("contentDigest"));
    }

    @Test
    void failingDeterministicChecksCannotMoveAnExistingReleasePointer() throws Exception {
        publish("1.0.1");
        principal.set(user("author"));
        var body = saveBody("1.0.2", 1);
        @SuppressWarnings("unchecked")
        var content = (Map<String, Object>) body.get("content");
        // Structurally bounded content can be staged, but this template admits at most four calls.
        content.put("maxToolCalls", 5);
        var draft = post("/drafts", body, 200).get("draftId").asString();
        assertEquals("FAILED", get("/" + draft + "/check", 200).get("result").asString());
        post("/drafts/" + draft + "/reviews", command(), 200);
        principal.set(user("reviewer"));
        error(post("/drafts/" + draft + "/publications", command(), 409), "SKILL_CHECK_FAILED");
        assertEquals("1.0.1", get("/" + SKILL + "/release", 200).get("currentVersion").asString());
        error(get("/" + SKILL + "/versions/1.0.2", 404), "NOT_FOUND");
    }

    @Test
    void everyReadChecksTheTrustedSkillScopeAndEnumerationRequiresWildcardAuthority() throws Exception {
        publish("1.0.1");
        var draft = get("/drafts", 200).get("items").get(0).get("draftId").asString();
        principal.set(scoped("fixture.other"));
        for (var path : List.of("/drafts", "/" + draft + "/check", "/" + SKILL + "/versions",
            "/" + SKILL + "/versions/1.0.1", "/" + SKILL + "/release")) {
            error(get(path, 403), "FORBIDDEN");
        }
        principal.set(scoped(SKILL));
        assertEquals("PASSED", get("/" + draft + "/check", 200).get("result").asString());
        assertEquals(1, get("/" + SKILL + "/versions", 200).get("items").size());
        assertEquals("1.0.1", get("/" + SKILL + "/versions/1.0.1", 200).get("version").asString());
        assertEquals("1.0.1", get("/" + SKILL + "/release", 200).get("currentVersion").asString());
        error(get("/drafts", 403), "FORBIDDEN");
        principal.set(scoped("*"));
        assertEquals(1, get("/drafts", 200).get("items").size());
    }

    @Test
    void httpQueriesBodiesAndPathVariablesAreClosedAndFailuresAreSanitized() throws Exception {
        for (var query : List.of("limit=0", "limit=51", "limit=1&limit=2", "after=bad", "tenantId=other")) {
            error(get("/drafts?" + query, 400), "INVALID_REQUEST");
        }
        error(get("/not-a-uuid/check", 400), "INVALID_REQUEST");
        error(get("/" + UUID.randomUUID() + "/check", 404), "NOT_FOUND");
        error(get("/drafts?after=" + UUID.randomUUID(), 404), "NOT_FOUND");
        error(get("/" + SKILL + "/versions?after=9.0.0", 404), "NOT_FOUND");
        var body = saveBody("1.0.1", 1);
        body.put("tenantId", "untrusted");
        error(post("/drafts", body, 400), "INVALID_REQUEST");
        body.remove("tenantId");
        error(post("/drafts?after=anything", body, 400), "INVALID_REQUEST");
        var malformed = http.perform(MockMvcRequestBuilders.post("/api/v1/skills/drafts")
            .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"private-fixture-value\"")).andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        error(CatalogJson.JSON.readTree(malformed.getResponse().getContentAsString()), "INVALID_REQUEST");
    }

    @Test
    void trustedTenantPermissionAndObjectBoundariesRejectWithoutCreatingDrafts() throws Exception {
        var draft = post("/drafts", saveBody("1.0.1", 1), 200).get("draftId").asString();
        principal.set(new Principal(new SubjectId("other"), new TenantId("other-fixture"),
            Set.of(Permission.SKILL_MANAGE, Permission.SKILL_READ), ResourceScope.tenantWide()));
        assertTrue(get("/drafts", 200).get("items").isEmpty());
        error(get("/" + draft + "/check", 404), "NOT_FOUND");
        error(post("/drafts/" + draft + "/reviews", command(), 404), "NOT_FOUND");
        principal.set(new Principal(new SubjectId("read-only"), TENANT, Set.of(Permission.SKILL_READ), ResourceScope.tenantWide()));
        error(post("/drafts", saveBody("1.0.2", 1), 403), "FORBIDDEN");
        principal.set(new Principal(new SubjectId("scoped"), TENANT,
            Set.of(Permission.SKILL_MANAGE, Permission.SKILL_READ),
            ResourceScope.of(Set.of(new ResourceRef(TENANT, "skill", "fixture.other")))));
        error(post("/drafts", saveBody("1.0.2", 1), 403), "FORBIDDEN");
        principal.set(user("author"));
        assertEquals(1, get("/drafts", 200).get("items").size());
        principal.set(new Principal(new SubjectId("no-read"), TENANT, Set.of(Permission.SKILL_MANAGE), ResourceScope.tenantWide()));
        error(get("/drafts", 403), "FORBIDDEN");
    }

    private void publish(String version) throws Exception {
        principal.set(user("author"));
        var draft = post("/drafts", saveBody(version, 1), 200).get("draftId").asString();
        var check = get("/" + draft + "/check", 200);
        assertEquals("PASSED", check.get("result").asString());
        assertEquals("not-run", check.get("modelComparison").asString());
        post("/drafts/" + draft + "/reviews", command(), 200);
        principal.set(user("reviewer"));
        post("/drafts/" + draft + "/publications", command(), 200);
    }

    private static Principal user(String subject) {
        return new Principal(new SubjectId(subject), TENANT,
            Set.of(Permission.SKILL_MANAGE, Permission.SKILL_READ), ResourceScope.tenantWide());
    }

    private static Principal scoped(String skillId) {
        return new Principal(new SubjectId("scoped-reader"), TENANT, Set.of(Permission.SKILL_READ),
            ResourceScope.of(Set.of(new ResourceRef(TENANT, "skill", skillId))));
    }

    private static LinkedHashMap<String, Object> command() {
        var command = new LinkedHashMap<String, Object>();
        command.put("requestId", UUID.randomUUID().toString());
        return command;
    }

    private static LinkedHashMap<String, Object> saveBody(String version, int editVersion) {
        var body = command();
        body.put("skillId", SKILL);
        body.put("version", version);
        body.put("editVersion", editVersion);
        var content = new SkillContent("readonly-current-incident-diagnosis-v1", "Fixture read-only diagnosis",
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"],\"additionalProperties\":false}",
            List.of("incident.get@2.0.0"), "current", "reasoning-structured-readonly", "forbidden",
            1, 1, 1024, 1000, List.of(new SkillContent.TestCase("fixture", "Fixture question", "{\"summary\":\"Fixture output\"}")));
        body.put("content", SkillJson.wire(content));
        return body;
    }

    private JsonNode get(String path, int status) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get("/api/v1/skills" + path)).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        return CatalogJson.JSON.readTree(response.getContentAsString());
    }

    private JsonNode post(String path, Map<String, Object> body, int status) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.post("/api/v1/skills" + path)
            .contentType(MediaType.APPLICATION_JSON).content(CatalogJson.JSON.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        return CatalogJson.JSON.readTree(response.getContentAsString());
    }

    private static void error(JsonNode body, String code) {
        assertEquals(CatalogJson.JSON.valueToTree(Map.of("error", code)), body);
    }
}
