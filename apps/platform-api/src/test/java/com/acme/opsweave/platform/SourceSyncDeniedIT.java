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
    "opsweave.zabbix.source-instance-id=zabbix-1"
})
class SourceSyncDeniedIT {
    @LocalServerPort
    int port;

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
