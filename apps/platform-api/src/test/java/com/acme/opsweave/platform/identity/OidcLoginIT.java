package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.PlatformApplication;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes=PlatformApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcLoginIT {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "http://127.0.0.1:5173", SECRET = "protocol-fixture-secret-not-production", SUBJECT = "operator-subject";
    static final RSAKey KEY;
    static final HttpServer IDP;
    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    static final Path GRANTS;
    static final Map<String,Map<String,String>> CODES = new ConcurrentHashMap<>();
    static final AtomicInteger EXCHANGES = new AtomicInteger();
    static volatile String fault = "";
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            GRANTS = Files.createTempFile("opsweave-oidc-grants-", ".json");
            IDP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); IDP.setExecutor(EXECUTOR);
            IDP.createContext("/jwks", e -> send(e, 200, new JWKSet(KEY.toPublicJWK()).toJSONObject()));
            IDP.createContext("/token", OidcLoginIT::exchange); IDP.start();
            grants(true, "operator-subject", true, List.of());
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    static String issuer() { return "http://127.0.0.1:" + IDP.getAddress().getPort(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.address", () -> "127.0.0.1"); p.add("opsweave.auth.mode", () -> "oidc"); p.add("opsweave.inventory.store", () -> "memory");
        p.add("opsweave.zabbix.mode", () -> "fixture"); p.add("opsweave.auth.oidc.issuer", OidcLoginIT::issuer);
        p.add("opsweave.auth.oidc.authorization-uri", () -> issuer() + "/authorize"); p.add("opsweave.auth.oidc.token-uri", () -> issuer() + "/token");
        p.add("opsweave.auth.oidc.jwk-set-uri", () -> issuer() + "/jwks"); p.add("opsweave.auth.oidc.client-id", () -> "opsweave");
        p.add("opsweave.auth.oidc.client-secret", () -> SECRET); p.add("opsweave.auth.oidc.public-origin", () -> ORIGIN);
        p.add("opsweave.auth.oidc.grants-file", GRANTS::toString); p.add("opsweave.auth.oidc.loopback-test", () -> "true");
    }
    @LocalServerPort int port;
    CookieManager cookies;
    HttpClient client;
    @BeforeEach void reset() throws Exception { fault = ""; grants(true, SUBJECT, true, List.of()); cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL); client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build(); }
    @AfterEach void closeClient() { client.close(); }
    @AfterAll static void close() throws Exception { IDP.stop(0); EXECUTOR.shutdownNow(); Files.deleteIfExists(GRANTS); }
    static void grants(boolean enabled, String external, boolean wide, List<Map<String,String>> resources) throws Exception {
        var row = Map.of("issuer", issuer(), "externalSubject", external, "subjectId", "operator", "tenantId", "oidc-fixture", "revision", 1,
            "enabled", enabled, "permissions", List.of("entity.read", "metric.read", "source.sync", "incident.read", "ai.diagnose", "evidence.read", "ai.insight.read"),
            "scope", Map.of("tenantWide", wide, "resources", resources));
        Files.writeString(GRANTS, JSON.writeValueAsString(Map.of("schemaVersion", "1.0", "grants", List.of(row))));
    }
    HttpResponse<String> request(String path, String method, String body, String csrf, String origin) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12));
        if (origin != null) b.header("Origin", origin); if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode session() throws Exception { var r = request("/api/v1/auth/session", "GET", null, null, null); assertEquals(200, r.statusCode()); return JSON.readTree(r.body()); }
    Map<String,String> start() throws Exception {
        var r = request("/api/v1/auth/login/opsweave", "GET", null, null, null); assertEquals(302, r.statusCode(), r.body());
        URI redirect = URI.create(r.headers().firstValue("Location").orElseThrow()); assertEquals(issuer() + "/authorize", redirect.toString().split("\\?")[0]);
        var q = params(redirect.getRawQuery()); assertEquals("S256", q.get("code_challenge_method")); assertNotNull(q.get("nonce")); assertNotNull(q.get("state")); assertEquals(ORIGIN + "/api/v1/auth/callback", q.get("redirect_uri")); return q;
    }
    HttpResponse<String> finish(Map<String,String> q) throws Exception { String code = UUID.randomUUID().toString(); CODES.put(code, q); return request("/api/v1/auth/callback?code=" + code + "&state=" + URLEncoder.encode(q.get("state"), StandardCharsets.UTF_8), "GET", null, null, null); }
    void login() throws Exception { var response = finish(start()); assertEquals(302, response.statusCode(), response.body()); assertEquals(ORIGIN + "/#/inventory", response.headers().firstValue("Location").orElseThrow()); }
    @Test void codePkceNonceCookieRotationCsrfAndLogout() throws Exception {
        var anonymous = session(); assertFalse(anonymous.get("authenticated").asBoolean()); assertEquals("oidc-protocol-test", anonymous.get("dataMode").asString());
        String before = cookies.getCookieStore().getCookies().getFirst().getValue(); login();
        String after = cookies.getCookieStore().getCookies().getFirst().getValue(); assertNotEquals(before, after);
        var current = session(); assertTrue(current.get("authenticated").asBoolean()); assertEquals("oidc-fixture", current.get("principal").get("tenantId").asString());
        assertFalse(current.toString().contains("attacker-tenant")); assertFalse(current.toString().contains(SECRET));
        String csrf = current.get("csrfToken").asString();
        assertEquals(403, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, anonymous.get("csrfToken").asString(), ORIGIN).statusCode());
        assertEquals(403, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, csrf, null).statusCode());
        assertEquals(415, request("/api/v1/integrations/zabbix/problems/ingest", "POST", null, csrf, ORIGIN).statusCode());
        assertTrue(session().get("authenticated").asBoolean());
        assertEquals(403, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, null, ORIGIN).statusCode());
        assertEquals(403, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, csrf, "https://evil.invalid").statusCode());
        assertEquals(200, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, csrf, ORIGIN).statusCode());
        assertEquals(400, request("/api/v1/entities/page?tenantId=attacker", "GET", null, null, null).statusCode());
        assertEquals(401, request("/api/v1/auth/callback?state=unsolicited&code=other", "GET", null, null, null).statusCode());
        assertTrue(session().get("authenticated").asBoolean());
        assertEquals(200, request("/api/v1/auth/logout", "POST", null, csrf, ORIGIN).statusCode());
        assertEquals(401, request("/api/v1/entities/page?limit=25", "GET", null, null, null).statusCode());
        assertFalse(session().get("authenticated").asBoolean());
    }
    @Test void badStateDoesNotExchangeCodeAndReplayedCallbackCannotLogIn() throws Exception {
        var q = start(); int count = EXCHANGES.get(); var bad = new HashMap<>(q); bad.put("state", "wrong-state"); assertEquals(401, finish(bad).statusCode()); assertEquals(count, EXCHANGES.get());
        q = start(); assertEquals(302, finish(q).statusCode()); assertEquals(401, finish(q).statusCode()); assertTrue(session().get("authenticated").asBoolean());
    }
    @Test void invalidProviderTokensNeverCreatePlatformSession() throws Exception {
        for (String mode : List.of("issuer", "audience", "nonce", "expired", "future-iat", "signature", "subject", "oversized", "chunked", "error")) {
            fault = mode; assertEquals(401, finish(start()).statusCode(), mode); assertFalse(session().get("authenticated").asBoolean(), mode);
        }
    }
    @Test void slowStreamingProviderBodyHitsAbsoluteDeadline() throws Exception {
        fault = "slow"; long before = System.nanoTime(); assertEquals(401, finish(start()).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 8500); assertFalse(session().get("authenticated").asBoolean());
    }
    @Test void operatorMappingRevocationAndInvalidFileFailClosed() throws Exception {
        grants(true, "someone-else", true, List.of()); assertEquals(403, finish(start()).statusCode());
        grants(true, SUBJECT, true, List.of()); login(); grants(false, SUBJECT, true, List.of());
        assertEquals(401, request("/api/v1/entities/page?limit=25", "GET", null, null, null).statusCode());
        grants(true, SUBJECT, true, List.of()); login(); Files.writeString(GRANTS, "{}");
        assertEquals(401, request("/api/v1/entities/page?limit=25", "GET", null, null, null).statusCode());
    }
    @Test void objectScopeIsAppliedBeforePagingAndDirectDetail() throws Exception {
        login(); var csrf = session().get("csrfToken").asString(); assertEquals(200, request("/api/v1/integrations/zabbix/hosts/sync", "POST", null, csrf, ORIGIN).statusCode());
        var all = JSON.readTree(request("/api/v1/entities/page?limit=25", "GET", null, null, null).body()).get("items"); assertTrue(all.size() >= 2);
        String visible = all.get(0).get("id").asString(), hidden = all.get(1).get("id").asString();
        grants(true, SUBJECT, false, List.of(Map.of("type", "entity", "id", visible)));
        assertEquals(401, request("/api/v1/entities/" + visible, "GET", null, null, null).statusCode()); login();
        var page = JSON.readTree(request("/api/v1/entities/page?limit=25", "GET", null, null, null).body()); assertEquals(1, page.get("items").size());
        assertEquals(200, request("/api/v1/entities/" + visible, "GET", null, null, null).statusCode());
        assertEquals(403, request("/api/v1/entities/" + hidden, "GET", null, null, null).statusCode());
    }
    static Map<String,String> params(String query) {
        var result = new HashMap<String,String>(); for (String pair : query.split("&")) { var p = pair.split("=", 2); result.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p.length == 2 ? p[1] : "", StandardCharsets.UTF_8)); } return result;
    }
    static void exchange(HttpExchange e) throws java.io.IOException {
        EXCHANGES.incrementAndGet();
        try {
            assertEquals("Basic " + Base64.getEncoder().encodeToString(("opsweave:" + SECRET).getBytes(StandardCharsets.UTF_8)), e.getRequestHeaders().getFirst("Authorization"));
            var form = params(new String(e.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8)); var auth = CODES.remove(form.get("code")); assertNotNull(auth);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertEquals(auth.get("code_challenge"), challenge); assertEquals(ORIGIN + "/api/v1/auth/callback", form.get("redirect_uri"));
            if (fault.equals("error")) { send(e, 503, Map.of("error", "unavailable")); return; }
            if (fault.equals("oversized")) { send(e, 200, Map.of("padding", "x".repeat(70000))); return; }
            if (fault.equals("chunked")) { e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, 0); e.getResponseBody().write(JSON.writeValueAsBytes(Map.of("padding", "x".repeat(70000)))); e.close(); return; }
            if (fault.equals("slow")) {
                e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush();
                Thread.sleep(6500); e.close(); return;
            }
            var now = Instant.now(); var claims = new JWTClaimsSet.Builder().issuer(fault.equals("issuer") ? "https://wrong.invalid" : issuer())
                .audience(fault.equals("audience") ? "wrong-client" : "opsweave").subject(fault.equals("subject") ? null : SUBJECT)
                .issueTime(Date.from(fault.equals("future-iat") ? now.plusSeconds(120) : now.minusSeconds(1))).expirationTime(Date.from(fault.equals("expired") ? now.minusSeconds(60) : now.plusSeconds(600)))
                .claim("nonce", fault.equals("nonce") ? "wrong-nonce" : auth.get("nonce")).claim("tenantId", "attacker-tenant").claim("permissions", List.of("*")).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture-key").build(), claims);
            jwt.sign(new RSASSASigner(fault.equals("signature") ? new RSAKeyGenerator(2048).generate() : KEY));
            send(e, 200, Map.of("token_type", "Bearer", "access_token", "fixture-access-token-must-not-leave-java", "expires_in", 600, "id_token", jwt.serialize()));
        } catch (Throwable invalid) { send(e, 400, Map.of("error", "invalid_grant")); }
    }
    static void send(HttpExchange e, int status, Object value) throws java.io.IOException { byte[] bytes = JSON.writeValueAsBytes(value); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
}
