package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.identity.RequestBoundaryFilter;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=request-boundary-test-token-not-a-secret","opsweave.auth.dev.tenant=request-boundary-http",
    "opsweave.auth.dev.permissions=entity.read","opsweave.zabbix.mode=fixture","opsweave.inventory.store=memory"})
class RequestBoundaryHttpIT {
    @LocalServerPort int port;
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)); }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private void bounded(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertNotNull(UUID.fromString(response.headers().firstValue(RequestBoundaryFilter.HEADER).orElseThrow()));
    }
    @Test void correlatesAuthorizedAndDeniedResponsesWithoutGrantingIdentityOrCachingData() throws Exception {
        String id=UUID.randomUUID().toString();
        var denied=send(request("/api/v1/entities").header(RequestBoundaryFilter.HEADER,id));
        assertEquals(401,denied.statusCode());assertEquals(id,denied.headers().firstValue(RequestBoundaryFilter.HEADER).orElseThrow());bounded(denied);
        var allowed=send(request("/api/v1/entities").header(RequestBoundaryFilter.HEADER,id).header("Authorization","Bearer request-boundary-test-token-not-a-secret"));
        assertEquals(200,allowed.statusCode());assertEquals(id,allowed.headers().firstValue(RequestBoundaryFilter.HEADER).orElseThrow());bounded(allowed);
        var generated=send(request("/api/v1/entities"));assertEquals(401,generated.statusCode());bounded(generated);
    }
    @Test void rejectsMalformedDuplicateTraceAndIdentityOverride() throws Exception {
        for(var r:new HttpRequest.Builder[]{request("/api/v1/entities").header(RequestBoundaryFilter.HEADER,"actor=admin"),
            request("/api/v1/entities").header(RequestBoundaryFilter.HEADER,UUID.randomUUID().toString()).header(RequestBoundaryFilter.HEADER,UUID.randomUUID().toString()),
            request("/api/v1/entities?tenantId=other").header(RequestBoundaryFilter.HEADER,UUID.randomUUID().toString())}) {
            var response=send(r);assertEquals(400,response.statusCode());bounded(response);assertFalse(response.body().contains("actor=admin"));
        }
    }
}
