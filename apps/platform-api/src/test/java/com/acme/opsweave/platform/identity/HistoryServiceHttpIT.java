package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.PlatformApplication;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes=PlatformApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class HistoryServiceHttpIT {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String DEV = "service-boundary-fixture-dev-token", TENANT = "history-service-fixture";
    static final long FROM = Instant.parse("2026-09-21T12:00:00Z").getEpochSecond();
    static final String PATH = "/api/v1/service/ingestion/items/20001/history?from=" + FROM + "&till=" + (FROM + 10) + "&limit=100";
    static final RSAKey KEY; static final HttpServer IDP; static final Path GRANTS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("service-fixture").generate();
            GRANTS = Files.createTempFile("opsweave-history-grants-", ".json"); Files.writeString(GRANTS, "{\"schemaVersion\":\"1.0\",\"grants\":[]}");
            IDP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IDP.createContext("/jwks", exchange -> { var bytes = JSON.writeValueAsBytes(new JWKSet(KEY.toPublicJWK()).toJSONObject()); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
            IDP.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    static String issuer() { return "http://127.0.0.1:" + IDP.getAddress().getPort(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.address", () -> "127.0.0.1"); p.add("opsweave.auth.mode", () -> "dev"); p.add("opsweave.auth.dev.token", () -> DEV);
        p.add("opsweave.auth.dev.tenant", () -> TENANT); p.add("opsweave.auth.dev.permissions", () -> "entity.read,metric.read,source.sync");
        p.add("opsweave.zabbix.mode", () -> "fixture"); p.add("opsweave.inventory.store", () -> "memory");
        p.add("opsweave.auth.history-service.enabled", () -> true); p.add("opsweave.auth.history-service.issuer", HistoryServiceHttpIT::issuer);
        p.add("opsweave.auth.history-service.jwk-set-uri", () -> issuer() + "/jwks"); p.add("opsweave.auth.history-service.grants-file", GRANTS::toString);
        p.add("opsweave.auth.history-service.loopback-test", () -> true);
    }
    @LocalServerPort int port;
    @Autowired HistoryServiceAccess access;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    String clientId, entity;
    Map<String,Object> row;
    @BeforeEach void seed() throws Exception {
        clientId = "worker-" + UUID.randomUUID(); assertEquals(200, call("/api/v1/integrations/zabbix/items/sync", "POST", DEV, Map.of()).statusCode());
        entity = JSON.readTree(call(PATH.replace("/service/ingestion", "/integrations/zabbix"), "GET", DEV, Map.of()).body()).get("entityId").asString();
        row = new LinkedHashMap<>(); row.put("issuer", issuer()); row.put("clientId", clientId); row.put("externalSubject", "worker-subject"); row.put("subjectId", "history-worker"); row.put("tenantId", TENANT);
        row.put("revision", 1); row.put("enabled", true); row.put("sourceInstanceId", "zabbix-1"); row.put("itemIds", List.of("20001")); row.put("entityIds", List.of(entity)); row.put("metricKeys", List.of("host.cpu.usage.user"));
        row.put("validFrom", Instant.now().minusSeconds(60).toString()); row.put("validUntil", Instant.now().plusSeconds(3600).toString()); row.put("from", FROM); row.put("till", FROM + 3600);
        row.put("maxWindowSeconds", 3600); row.put("maxPoints", 500); row.put("requestsPerMinute", 120); save();
    }
    @AfterEach void closeClient() { http.close(); }
    @AfterAll static void close() throws Exception { IDP.stop(0); Files.deleteIfExists(GRANTS); }
    void save() throws Exception { Files.writeString(GRANTS, JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "grants", List.of(row)))); }
    HttpResponse<String> call(String path, String method, String token, Map<String,String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token); headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    String token(String fault) throws Exception {
        var now = Instant.now(); var claims = new JWTClaimsSet.Builder().issuer(fault.equals("issuer") ? "https://other.invalid" : issuer())
            .audience(fault.equals("audience") ? "opsweave" : "opsweave-history").subject(fault.equals("subject") ? "other-worker" : "worker-subject")
            .claim("client_id", fault.equals("client") ? "other-client" : clientId).claim("scope", fault.equals("scope") ? "shell" : "opsweave.history.read")
            .jwtID(fault.equals("jti") ? null : UUID.randomUUID().toString()).issueTime(Date.from(now.plusSeconds(fault.equals("future") ? 120 : -1)))
            .expirationTime(Date.from(now.plusSeconds(fault.equals("expired") ? -60 : fault.equals("lifetime") ? 901 : 300)))
            .claim("tenantId", "attacker-tenant").claim("permissions", List.of("*")).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(fault.equals("algorithm") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256).type(new JOSEObjectType(fault.equals("type") ? "JWT" : "at+jwt")).keyID("service-fixture").build(), claims);
        jwt.sign(new RSASSASigner(fault.equals("signature") ? new RSAKeyGenerator(2048).generate() : KEY)); return jwt.serialize();
    }
    @Test void verifiedServiceIdentityReadsOnlyMappedTenantAndCannotUseUserRoutes() throws Exception {
        var token = token(""); var response = call(PATH, "GET", token, Map.of()); assertEquals(200, response.statusCode(), response.body());
        var page = JSON.readTree(response.body()); assertEquals(TENANT, page.get("tenantId").asString()); assertEquals(entity, page.get("entityId").asString());
        assertEquals("labeled-fixture", page.get("dataMode").asString()); assertEquals(3, page.get("points").size());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(401, call(PATH.replace("/service/ingestion", "/integrations/zabbix"), "GET", token, Map.of()).statusCode());
        assertEquals(401, call(PATH, "GET", DEV, Map.of()).statusCode());
        assertEquals(403, call(PATH.replace("20001", "20002"), "GET", token, Map.of()).statusCode());
        assertEquals(403, call("/api/v1/service/ingestion/items/20001/sync", "POST", token, Map.of()).statusCode());
    }
    @Test void jwtProfileWrongSubjectAndUnmappedClientFailClosed() throws Exception {
        for (String fault : List.of("issuer", "audience", "type", "scope", "future", "expired", "lifetime", "jti", "algorithm", "signature"))
            assertEquals(401, call(PATH, "GET", token(fault), Map.of()).statusCode(), fault);
        for (String fault : List.of("subject", "client")) assertEquals(403, call(PATH, "GET", token(fault), Map.of()).statusCode(), fault);
    }
    @Test void queryIdentityAndBrowserHeadersCannotExpandServiceScope() throws Exception {
        var token = token("");
        for (String suffix : List.of("&tenantId=other", "&from=0", "&limit=1", "&afterClock=1", "&extra=1", "&afterClock=" + FROM + "&afterNs=1000000000"))
            assertEquals(400, call(PATH + suffix, "GET", token, Map.of()).statusCode(), suffix);
        assertEquals(403, call(PATH.replace("from=" + FROM, "from=" + (FROM - 1)), "GET", token, Map.of()).statusCode());
        for (String header : List.of("Cookie", "Origin", "Forwarded", "X-Forwarded-Proto")) assertEquals(401, call(PATH, "GET", token, Map.of(header, "untrusted")).statusCode(), header);
        assertEquals(400, call(PATH, "GET", token, Map.of("X-Tenant-ID", "other")).statusCode());
    }
    @Test void revokedExpiredChangedResourcesAndBrokenGrantFileNeverReuseAuthorization() throws Exception {
        var token = token(""); assertEquals(200, call(PATH, "GET", token, Map.of()).statusCode());
        row.put("enabled", false); save(); assertEquals(403, call(PATH, "GET", token, Map.of()).statusCode());
        row.put("enabled", true); row.put("entityIds", List.of(UUID.randomUUID().toString())); save(); assertEquals(404, call(PATH, "GET", token, Map.of()).statusCode());
        row.put("entityIds", List.of(entity)); row.put("validUntil", Instant.now().minusSeconds(1).toString()); save(); assertEquals(403, call(PATH, "GET", token, Map.of()).statusCode());
        Files.writeString(GRANTS, "{}"); assertEquals(503, call(PATH, "GET", token, Map.of()).statusCode());
        Files.delete(GRANTS); assertEquals(503, call(PATH, "GET", token, Map.of()).statusCode());
    }
    @Test void perClientRateAndSharedConcurrencyBudgetsAreApplied() throws Exception {
        var token = token(""); row.put("requestsPerMinute", 1); save(); assertEquals(200, call(PATH, "GET", token, Map.of()).statusCode()); assertEquals(429, call(PATH, "GET", token, Map.of()).statusCode());
        row.put("requestsPerMinute", 120); save(); var held = new ArrayList<HistoryServiceAccess.Permit>();
        try {
            for (int i = 0; i < 4; i++) held.add(access.authorize(mock(token)));
            assertEquals(429, assertThrows(HistoryServiceAccess.Rejected.class, () -> access.authorize(mock(token))).status);
        } finally { held.forEach(HistoryServiceAccess.Permit::close); }
        try (var available = access.authorize(mock(token))) { assertEquals(TENANT, available.principal.tenantId().value()); }
    }
    MockHttpServletRequest mock(String token) {
        var request = new MockHttpServletRequest("GET", "/api/v1/service/ingestion/items/20001/history"); request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", "Bearer " + token);
        request.addParameter("from", String.valueOf(FROM)); request.addParameter("till", String.valueOf(FROM + 10)); request.addParameter("limit", "100"); return request;
    }
}
