package com.acme.opsweave.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeDispatcherTest {
    private static OpsweaveProperties properties(String mode) { return new OpsweaveProperties(new OpsweaveProperties.Auth(mode, true, null), null, null); }
    @Test void onlyExplicitLoopbackOriginsAndDevIdentityAreAccepted() {
        for (String origin : new String[]{"https://127.0.0.1:9", "http://localhost:9", "http://0.0.0.0:9", "http://127.0.0.1", "http://127.0.0.1:9/other", "http://user:pass@127.0.0.1:9", "http://127.0.0.1:9?x=y"}) assertThrows(IllegalStateException.class, () -> RuntimeDispatcher.endpoint(origin));
        assertEquals("/api/v1/current-diagnoses", RuntimeDispatcher.endpoint("http://127.0.0.1:9").getPath());
        assertThrows(IllegalStateException.class, () -> new RuntimeDispatcher(properties("closed"), "http://127.0.0.1:9"));
        try (var disabled = new RuntimeDispatcher(properties("closed"), "")) { assertEquals(ToolFailure.Code.UNAVAILABLE, assertThrows(ToolFailure.class, () -> disabled.dispatch("Bearer local-test", "{}")).code()); }
    }
    @Test void forwardsOnlyCallingCredentialToFixedEndpointWithoutFollowingRedirectsOrRetrying() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var calls = new AtomicInteger();
        server.createContext("/api/v1/current-diagnoses", exchange -> {
            calls.incrementAndGet(); assertEquals("Bearer local-test", exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("X-OpsWeave-Runtime-Key")); assertEquals("POST", exchange.getRequestMethod());
            assertEquals("{}", new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Location", "/must-not-follow"); exchange.sendResponseHeaders(307, -1); exchange.close();
        }); server.start();
        try (var dispatcher = new RuntimeDispatcher(properties("dev"), "http://127.0.0.1:" + server.getAddress().getPort())) {
            assertEquals(ToolFailure.Code.UNAVAILABLE, assertThrows(ToolFailure.class, () -> dispatcher.dispatch("Bearer local-test", "{}")).code()); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    @Test void rejectsOversizedSuccessfulResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/current-diagnoses", exchange -> { byte[] body = new byte[131073]; exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); } }); server.start();
        try (var dispatcher = new RuntimeDispatcher(properties("dev"), "http://127.0.0.1:" + server.getAddress().getPort())) {
            assertEquals(ToolFailure.Code.UNAVAILABLE, assertThrows(ToolFailure.class, () -> dispatcher.dispatch("Bearer local-test", "{}")).code());
        } finally { server.stop(0); }
    }
}
