package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ClientCredentialsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SECRET = "fixture-service-secret-not-production", TOKEN = "eyJ0eXAiOiJhdCtqd3QifQ.eyJhdWQiOiJvcHN3ZWF2ZS1oaXN0b3J5In0.fixtureSignature";
    static final HistoryStream STREAM = new HistoryStream(new TenantId("tenant-demo"), "zabbix-1", "20001", "fixture");
    static final HistoryWindow WINDOW = new HistoryWindow(1789992000, 1789992010, null, 100);
    @Test void oneClientCredentialsRequestCachesOnlyUntilMonotonicDeadlineAndNeverContainsTenant() throws Exception {
        try (var stub = new Stub()) {
            var time = new AtomicLong(1000); var auth = new ClientCredentialsAuthorization(stub.settings(), time::get);
            assertEquals("Bearer " + TOKEN, auth.authorization()); assertEquals("Bearer " + TOKEN, auth.authorization()); assertEquals(1, stub.tokenCalls);
            assertEquals("grant_type=client_credentials&scope=opsweave.history.read", stub.tokenBody);
            assertEquals("Basic " + Base64.getEncoder().encodeToString(("worker:" + SECRET).getBytes(StandardCharsets.UTF_8)), stub.tokenAuth);
            time.addAndGet(Duration.ofSeconds(295).toNanos()); auth.authorization(); assertEquals(2, stub.tokenCalls);
            assertFalse(auth.checkpointIdentity().contains(SECRET)); assertTrue(auth.checkpointIdentity().endsWith("#worker"));
        }
    }
    @Test void serviceReaderUsesDedicatedRouteAnd401ClearsTokenWithoutRetryingTheRead() throws Exception {
        try (var stub = new Stub()) {
            var auth = new ClientCredentialsAuthorization(stub.settings()); var reader = new PlatformHistoryReader(stub.origin(), auth, "labeled-fixture", true);
            stub.historyStatus = 401; assertEquals(Failure.Code.SOURCE_FAILED, assertThrows(Failure.class, () -> reader.read(STREAM, WINDOW)).code());
            assertEquals(1, stub.tokenCalls); assertEquals(1, stub.historyCalls);
            stub.historyStatus = 200; var slice = reader.read(STREAM, WINDOW); assertEquals(1, slice.page().points().size());
            assertEquals(2, stub.tokenCalls); assertEquals(2, stub.historyCalls); assertEquals("Bearer " + TOKEN, stub.historyAuth);
            assertTrue(stub.historyPath.startsWith("/api/v1/service/ingestion/items/20001/history?")); assertFalse(stub.historyPath.contains("tenant"));
            assertNotEquals(reader.checkpointIdentity(), new PlatformHistoryReader(stub.origin(), "fixture-development-token-not-production", "labeled-fixture").checkpointIdentity());
        }
    }
    @Test void providerErrorsAndUnboundedOrUnexpectedTokensNeverFallBackOrRetry() throws Exception {
        try (var stub = new Stub()) {
            for (String mode : List.of("status", "redirect", "html", "oversized", "scope", "lifetime", "extra", "duplicate")) {
                stub.fault = mode; int before = stub.tokenCalls;
                assertThrows(IllegalStateException.class, () -> new ClientCredentialsAuthorization(stub.settings()).authorization(), mode);
                assertEquals(before + 1, stub.tokenCalls, mode); assertEquals(0, stub.historyCalls);
            }
        }
    }
    @Test void slowTokenBodyHasAWholeResponseDeadline() throws Exception {
        try (var stub = new Stub()) {
            stub.fault = "slow"; long before = System.nanoTime(); assertThrows(IllegalStateException.class, () -> new ClientCredentialsAuthorization(stub.settings()).authorization());
            assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 6000); assertEquals(1, stub.tokenCalls);
        }
    }
    @Test void transportRequiresExplicitHttpsOrLoopbackTestAndSecretsStayRedacted() {
        var settings = new HistoryServiceClientSettings("http://remote.invalid/token", "worker", SECRET, true);
        assertThrows(IllegalArgumentException.class, settings::validate); assertFalse(settings.toString().contains(SECRET));
        assertThrows(IllegalArgumentException.class, () -> new HistoryServiceClientSettings("http://127.0.0.1:1234/token", "worker", SECRET, false).validate());
        assertThrows(IllegalArgumentException.class, () -> new HistoryServiceClientSettings("https://id.invalid/token?secret=x", "worker", SECRET, false).validate());
        assertThrows(IllegalArgumentException.class, () -> new HistoryServiceClientSettings("https://id.invalid/token", "worker", "short", false).validate());
        new HistoryServiceClientSettings("https://id.invalid/token", "worker", SECRET, false).validate();
    }
    static final class Stub implements AutoCloseable {
        final HttpServer server; final ExecutorService executor = Executors.newFixedThreadPool(2);
        volatile int tokenCalls, historyCalls, historyStatus = 200;
        volatile String tokenAuth, tokenBody, historyPath, historyAuth, fault = "";
        final String page;
        Stub() throws Exception {
            try (var input = getClass().getClassLoader().getResourceAsStream("contracts/metric-history-page.json")) { page = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    int status = 200; String body; String contentType = "application/json";
                    if (exchange.getRequestURI().getPath().equals("/token")) {
                        tokenCalls++; tokenAuth = exchange.getRequestHeaders().getFirst("Authorization"); tokenBody = new String(exchange.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8);
                        if (fault.equals("slow")) { exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(6500); return; }
                        var response = new LinkedHashMap<String,Object>(); response.put("access_token", TOKEN); response.put("token_type", "Bearer"); response.put("expires_in", fault.equals("lifetime") ? 901 : 300);
                        if (fault.equals("scope")) response.put("scope", "shell"); if (fault.equals("extra")) response.put("refresh_token", "unexpected");
                        body = JSON.writeValueAsString(response);
                        if (fault.equals("duplicate")) body = body.replace("\"expires_in\":300", "\"expires_in\":300,\"expires_in\":300");
                        if (fault.equals("oversized")) body = "x".repeat(32769);
                        if (fault.equals("status")) status = 503;
                        if (fault.equals("redirect")) { status = 302; exchange.getResponseHeaders().set("Location", origin() + "/unexpected"); }
                        if (fault.equals("html")) contentType = "text/html";
                    } else { historyCalls++; historyPath = exchange.getRequestURI().toString(); historyAuth = exchange.getRequestHeaders().getFirst("Authorization"); body = page; status = historyStatus; }
                    var bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
            }); server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        HistoryServiceClientSettings settings() { return new HistoryServiceClientSettings(origin() + "/token", "worker", SECRET, true); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
