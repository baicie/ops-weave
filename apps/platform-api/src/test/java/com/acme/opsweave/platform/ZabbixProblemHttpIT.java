package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=problem-test-token-not-for-other-use", "opsweave.auth.dev.subject=problem-user",
    "opsweave.auth.dev.tenant=tenant-problem-http", "opsweave.auth.dev.permissions=source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"
})
class ZabbixProblemHttpIT {
    @LocalServerPort int port;
    private static final String ROOT = "/api/v1/integrations/zabbix/problems";
    private static final long FROM = Instant.parse("2026-09-21T11:59:00Z").getEpochSecond();
    private static final long TILL = FROM + 3600;
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void readsExplicitFixtureRecoveryAndPagesWithoutClaimingPersistence() throws Exception {
        var response = call("?from=" + FROM + "&till=" + TILL + "&limit=1", true);
        assertEquals(200, response.statusCode(), response.body()); var first = json.readTree(response.body());
        assertEquals("not-persisted", first.get("storage").asString()); assertEquals("labeled-fixture", first.get("dataMode").asString());
        assertEquals("RECOVERED", first.get("items").get(0).get("state").asString());
        String cursor = first.get("nextAfterEventId").asString(); assertEquals("30001", cursor);
        var second = json.readTree(call("?from=" + FROM + "&till=" + TILL + "&limit=1&afterEventId=" + cursor, true).body());
        assertEquals("ACTIVE", second.get("items").get(0).get("state").asString());
        assertTrue(second.get("items").get(0).get("suppressed").asBoolean()); assertTrue(second.get("nextAfterEventId").isNull());
        Path dir = Path.of("../../.tmp/problem-http"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("external-problem-page.json"), first.toString());
        Files.writeString(dir.resolve("external-problem.json"), first.get("items").get(0).toString());
    }
    @Test void rejectsUnknownParametersIdentityOverrideAndOutOfBudgetReads() throws Exception {
        assertEquals(401, call("?from=" + FROM + "&till=" + TILL, false).statusCode());
        for (String query : new String[]{"from=1&till=90000", "from=2&till=1", "from=a&till=2", "from=1&till=2&limit=101",
            "from=1&till=2&afterEventId=01", "from=1&till=2&endpoint=http://example.com", "from=1&till=2&from=1"}) {
            assertEquals(400, call("?" + query, true).statusCode(), query);
        }
        int override = call("?from=1&till=2&tenantId=other", true).statusCode(); assertTrue(override == 400 || override == 403);
    }
    private HttpResponse<String> call(String query, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ROOT + query)).timeout(Duration.ofSeconds(10));
        if (auth) request.header("Authorization", "Bearer problem-test-token-not-for-other-use");
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
