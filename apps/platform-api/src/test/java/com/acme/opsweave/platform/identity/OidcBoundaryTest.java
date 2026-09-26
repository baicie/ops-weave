package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OidcBoundaryTest {
    @TempDir Path temp;
    static final JsonMapper JSON = JsonMapper.builder().build();
    OidcSettings settings; FileIdentityGrants grants; OidcSessions sessions; RuntimeDelegations delegations;
    MockHttpServletRequest browser;
    JsonNode diagnosis;
    @BeforeEach void setup() throws Exception {
        settings = new OidcSettings("http://127.0.0.1:18001", "http://127.0.0.1:18001/authorize", "http://127.0.0.1:18001/token", "http://127.0.0.1:18001/jwks", "opsweave", "fixture-secret-not-production", "http://127.0.0.1:18002", temp.resolve("grants.json").toString(), true, 900);
        writeGrant(1, true); grants = new FileIdentityGrants(settings); sessions = new OidcSessions(grants, settings); delegations = new RuntimeDelegations(sessions);
        browser = new MockHttpServletRequest(); sessions.establish(browser, settings.issuer(), "sub", Instant.now().plusSeconds(600));
        diagnosis = JSON.readTree("""
            {"runId":"11111111-1111-4111-8111-111111111111","incidentId":"22222222-2222-4222-8222-222222222222","question":"What happened?",
             "timeRange":{"from":"2026-09-25T00:00:00Z","to":"2026-09-25T00:15:00Z"},"knowledgeMode":"current"}
            """);
    }
    void writeGrant(int revision, boolean enabled) throws Exception {
        Files.writeString(Path.of(settings.grantsFile()), JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "grants", List.of(Map.of(
            "issuer", settings.issuer(), "externalSubject", "sub", "subjectId", "operator", "tenantId", "fixture", "revision", revision, "enabled", enabled,
            "permissions", List.of("entity.read", "ai.diagnose"), "scope", Map.of("tenantWide", true, "resources", List.of()))))));
    }
    MockHttpServletRequest delegated(RuntimeDelegations.Lease lease, String method, String path, JsonNode body) {
        var request = new MockHttpServletRequest(method, path); request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", lease.authorization());
        if (body != null) request.setContent(JSON.writeValueAsBytes(body)); return request;
    }
    MockHttpServletRequest read(RuntimeDelegations.Lease lease) { return delegated(lease, "GET", "/api/v1/ai/insights/11111111-1111-4111-8111-111111111111", null); }
    @Test void boundedLeasePinsRunReadSessionAndRequestAndReplaysBody() throws Exception {
        try (var lease = delegations.issue(browser, diagnosis)) {
            var request = delegated(lease, "POST", "/api/v1/ai/read-sessions", diagnosis); var wrapped = delegations.authorize(request);
            assertEquals(diagnosis, JSON.readTree(wrapped.getInputStream().readAllBytes()));
            UUID id = UUID.randomUUID(); RuntimeDelegations.bindReadSession(wrapped, id);
            var tool = delegated(lease, "POST", "/api/v1/tools/incident.get/2.0.0", diagnosis); tool.addHeader("X-OpsWeave-Read-Session", id.toString());
            assertNotNull(delegations.authorize(tool)); lease.checkActive();
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(delegated(lease, "POST", "/api/v1/ai/read-sessions", diagnosis)));
            var wrong = delegated(lease, "POST", "/api/v1/tools/incident.get/2.0.0", diagnosis); wrong.addHeader("X-OpsWeave-Read-Session", UUID.randomUUID().toString());
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(wrong));
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(delegated(lease, "POST", "/api/v1/integrations/zabbix/hosts/sync", null)));
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(delegated(lease, "GET", "/api/v1/ai/insights/other", null)));
        }
    }
    @Test void callAndConcurrencyBudgetsAndCloseAreEnforced() {
        var leases = new ArrayList<RuntimeDelegations.Lease>();
        try {
            for (int i = 0; i < 4; i++) leases.add(delegations.issue(browser, diagnosis));
            assertThrows(com.acme.opsweave.aicontrol.domain.ToolFailure.class, () -> delegations.issue(browser, diagnosis));
            var lease = leases.getFirst(); for (int i = 0; i < 8; i++) assertNotNull(delegations.authorize(read(lease)));
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(read(lease))); lease.close();
            assertThrows(RuntimeDelegations.Denied.class, lease::checkActive);
            assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(read(lease)));
            try (var available = delegations.issue(browser, diagnosis)) { available.checkActive(); }
        } finally { leases.forEach(RuntimeDelegations.Lease::close); }
    }
    @Test void modelBudgetDelegationHasOneBoundReservationAndReportWithoutIncreasingReadBudget() {
        try(var lease=delegations.issue(browser,diagnosis)) {
            var opened=delegations.authorize(delegated(lease,"POST","/api/v1/ai/read-sessions",diagnosis));var id=UUID.randomUUID();RuntimeDelegations.bindReadSession(opened,id);
            var body=JSON.readTree(JSON.writeValueAsString(Map.of("runId",diagnosis.get("runId").asString(),"sessionId",id.toString())));
            String reserve="/api/v1/ai/model-calls/reservations",report="/api/v1/ai/model-calls/reports";
            assertThrows(RuntimeDelegations.Denied.class,()->delegations.authorize(delegated(lease,"POST",report,body)));
            var wrong=JSON.readTree(JSON.writeValueAsString(Map.of("runId",UUID.randomUUID().toString(),"sessionId",id.toString())));
            assertThrows(RuntimeDelegations.Denied.class,()->delegations.authorize(delegated(lease,"POST",reserve,wrong)));
            assertNotNull(delegations.authorize(delegated(lease,"POST",reserve,body)));
            assertThrows(RuntimeDelegations.Denied.class,()->delegations.authorize(delegated(lease,"POST",reserve,body)));
            assertNotNull(delegations.authorize(delegated(lease,"POST",report,body)));
            assertThrows(RuntimeDelegations.Denied.class,()->delegations.authorize(delegated(lease,"POST",report,body)));
            for(int i=0;i<7;i++)assertNotNull(delegations.authorize(read(lease)));
            assertThrows(RuntimeDelegations.Denied.class,()->delegations.authorize(read(lease)));
        }
    }
    @Test void delegationExpiresExactlyAtItsAbsoluteDeadline() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        var clock = new Clock() { public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now.get(); } };
        var bounded = new RuntimeDelegations(sessions, clock);
        try (var lease = bounded.issue(browser, diagnosis)) {
            now.set(now.get().plusSeconds(65).minusNanos(1)); lease.checkActive(); assertNotNull(bounded.authorize(read(lease)));
            now.set(now.get().plusNanos(1)); assertThrows(RuntimeDelegations.Denied.class, lease::checkActive); assertThrows(RuntimeDelegations.Denied.class, () -> bounded.authorize(read(lease)));
        }
    }
    @Test void browserHeadersNonLoopbackAndQueryCannotUseDelegation() {
        try (var lease = delegations.issue(browser, diagnosis)) {
            for (String header : List.of("Cookie", "Origin")) { var request = read(lease); request.addHeader(header, "untrusted"); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(request)); }
            var remote = read(lease); remote.setRemoteAddr("192.0.2.1"); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(remote));
            var query = read(lease); query.addParameter("tenantId", "other"); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(query));
            var duplicate = read(lease); duplicate.addHeader("Authorization", lease.authorization()); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(duplicate));
        }
    }
    @Test void logoutExpiryGrantChangeAndNewLoginRevokeInflightCapabilities() throws Exception {
        try (var lease = delegations.issue(browser, diagnosis)) {
            sessions.establish(browser, settings.issuer(), "sub", Instant.now().plusSeconds(600));
            assertThrows(RuntimeDelegations.Denied.class, lease::checkActive); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(read(lease)));
        }
        try (var lease = delegations.issue(browser, diagnosis)) {
            writeGrant(2, true); assertThrows(RuntimeDelegations.Denied.class, lease::checkActive); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(read(lease)));
        }
        sessions.establish(browser, settings.issuer(), "sub", Instant.now().plusSeconds(600));
        try (var lease = delegations.issue(browser, diagnosis)) { sessions.clear(browser); assertThrows(RuntimeDelegations.Denied.class, () -> delegations.authorize(read(lease))); }
        var expired = new MockHttpServletRequest(); var identity = sessions.establish(expired, settings.issuer(), "sub", Instant.now().plusSeconds(600));
        expired.getSession().setAttribute(OidcSessions.ATTR, new OidcSessions.Identity(identity.id(), identity.issuer(), identity.subject(), identity.grantDigest(), Instant.now().minusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> sessions.resolve(expired.getSession()));
        assertThrows(IllegalArgumentException.class, () -> delegations.issue(expired, diagnosis));
    }
    @Test void grantsAreStrictBoundedAndFailClosedWithoutRetainingOldValue() throws Exception {
        var valid = Files.readString(Path.of(settings.grantsFile()));
        for (String bad : List.of("{}", valid.replace("\"entity.read\"", "\"shell\""), valid.replace("\"revision\":1", "\"revision\":0"), valid.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\""), "x".repeat(262145))) {
            Files.writeString(Path.of(settings.grantsFile()), bad); assertThrows(RuntimeException.class, () -> grants.find("sub"));
        }
        writeGrant(1, false); assertThrows(RuntimeException.class, () -> grants.find("sub"));
        Files.delete(Path.of(settings.grantsFile())); assertThrows(RuntimeException.class, () -> grants.find("sub"));
    }
    @Test void productionConfigurationRequiresHttpsAndSecureHostCookie() throws Exception {
        settings.validate("127.0.0.1"); assertThrows(IllegalStateException.class, () -> settings.validate("0.0.0.0"));
        var prod = new OidcSettings("https://id.example", "https://id.example/authorize", "https://id.example/token", "https://id.example/jwks", "opsweave", "fixture-secret-not-production", "https://ops.example", settings.grantsFile(), false, 900);
        prod.validate("127.0.0.1"); var context = new MockServletContext(); new OidcSecurityConfiguration().oidcCookies(prod).onStartup(context);
        var cookie = context.getSessionCookieConfig(); assertEquals("__Host-opsweave", cookie.getName()); assertTrue(cookie.isSecure()); assertTrue(cookie.isHttpOnly()); assertEquals("/", cookie.getPath()); assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertFalse(prod.toString().contains(prod.clientSecret()));
        var unsafe = new OidcSettings(settings.issuer(), settings.authorizationUri(), settings.tokenUri(), settings.jwkSetUri(), settings.clientId(), settings.clientSecret(), settings.publicOrigin(), settings.grantsFile(), false, 900);
        assertThrows(IllegalStateException.class, () -> unsafe.validate("127.0.0.1"));
    }
}
