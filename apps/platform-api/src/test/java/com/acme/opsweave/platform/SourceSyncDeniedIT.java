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
    "opsweave.auth.dev.permissions=entity.read",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1",
    "opsweave.inventory.store=memory"
})
class SourceSyncDeniedIT {
    @Test void entityReadDoesNotGrantSnapshotIngestOrConfig() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/cmdb/snapshots/config")).header("Authorization","Bearer test-dev-token-please-do-not-use-elsewhere").GET().build();
        assertEquals(403,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @LocalServerPort
    int port;

    @Test
    void entityReadDoesNotGrantMetricQueryAccess() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/v1/entities/11111111-1111-1111-1111-111111111111/metrics/up/series?from=0&till=60"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere")
            .GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void pipelineReadWithoutSourcePermissionIsForbidden() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/v1/integrations/zabbix/hosts/pipeline/versions/zabbix-host-default/1"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere")
            .GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void hostSyncWithoutPermissionIsForbidden() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/integrations/zabbix/hosts/sync"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer test-dev-token-please-do-not-use-elsewhere")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode(), response.body());
    }
}
