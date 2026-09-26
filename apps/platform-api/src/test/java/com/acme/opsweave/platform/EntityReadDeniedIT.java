package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev",
    "opsweave.auth.dev.token=test-dev-token-please-do-not-use-elsewhere",
    "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo",
    "opsweave.auth.dev.permissions=source.sync",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1",
    "opsweave.inventory.store=memory"
})
class EntityReadDeniedIT {
    @Test void problemObservationsRequireIncidentAndEntityRead() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/incidents/11111111-1111-1111-1111-111111111111/problem-observations?version=1&from=0&till=60"))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer test-dev-token-please-do-not-use-elsewhere").GET().build();
        assertEquals(403,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void sourceReviewReadAndWritesRequireEntityManagement() throws Exception {
        for (String method : new String[]{"GET", "POST"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entities/11111111-1111-1111-1111-111111111111/source-reviews"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere").method(method,HttpRequest.BodyPublishers.ofString("{}"));
            assertEquals(403,HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void observationsRequireEntityReadPermission() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entities/11111111-1111-1111-1111-111111111111/observations?from=0&till=60"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere").GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @LocalServerPort
    int port;

    @Test
    void sourceSyncDoesNotGrantMetricQueryAccess() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/v1/entities/11111111-1111-1111-1111-111111111111/metrics/up/series?from=0&till=60"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere")
            .GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void entityReadWithoutPermissionIsForbidden() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entities"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere")
            .GET()
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void pagedReadWithoutPermissionIsForbidden() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entities/page"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere").GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
