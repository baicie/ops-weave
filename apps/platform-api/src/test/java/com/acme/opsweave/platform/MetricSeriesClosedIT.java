package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=metric-closed-test-token-not-for-real-use", "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo", "opsweave.auth.dev.permissions=entity.read,metric.read",
    "opsweave.zabbix.mode=fixture", "opsweave.inventory.store=memory", "opsweave.metrics.victoria-url="
})
class MetricSeriesClosedIT {
    @LocalServerPort int port;
    @Autowired IngestZabbixHostsUseCase hosts;
    @Autowired IngestZabbixItemsUseCase items;

    @Test
    void readOnlyPrincipalReachesClosedStoreWithoutReceivingSyncPermission() throws Exception {
        // Fixture preparation is outside HTTP; the authenticated reader cannot synchronize sources.
        var seed = new Principal(new SubjectId("fixture-seeder"), new TenantId("tenant-demo"),
            Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        hosts.execute(seed, "zabbix-1");
        items.execute(seed, "zabbix-1");
        var json = JsonMapper.builder().build();
        assertEquals(403, call("POST", "/api/v1/integrations/zabbix/hosts/sync").statusCode());
        var entity = json.readTree(call("GET", "/api/v1/entities").body()).get("items").get(0).get("id").asString();
        long till = Instant.now().getEpochSecond();
        var response = call("GET", "/api/v1/entities/" + entity + "/metrics/host.cpu.usage.user/series?from=" + (till - 60) + "&till=" + till);
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("SOURCE_UNAVAILABLE", json.readTree(response.body()).get("error").asString());
        assertFalse(response.body().contains("series"));
    }

    private HttpResponse<String> call(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer metric-closed-test-token-not-for-real-use")
            .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
