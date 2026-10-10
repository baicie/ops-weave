package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * OW-ST06 overreach negatives: a trusted loopback identity without entity.manage or source.sync is
 * refused every write of the acceptance chains with an explicit 403, while read APIs it is granted
 * keep working. Menu visibility is never the permission boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=owst06-denied-fixture-only-32-characters",
    "opsweave.auth.dev.subject=owst06-reader",
    "opsweave.auth.dev.permissions=entity.read",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=owst06-fixture",
    "opsweave.inventory.store=postgres"
})
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresOwSt06DeniedIT {
    static final String TOKEN = "owst06-denied-fixture-only-32-characters";
    static final String TENANT = "ow-st06-denied-" + (System.getenv("OPSWEAVE_OW_ST06_SEED") == null ? UUID.randomUUID() : System.getenv("OPSWEAVE_OW_ST06_SEED"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("opsweave.auth.dev.tenant", () -> TENANT);
        r.add("opsweave.inventory.jdbc-url", () -> System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        r.add("opsweave.inventory.jdbc-user", () -> System.getenv("OPSWEAVE_TEST_JDBC_USER"));
        r.add("opsweave.inventory.jdbc-password", () -> System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    @LocalServerPort int port;

    HttpResponse<String> call(String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + TOKEN);
        if (body == null) b.GET(); else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpClient http() { return HttpClient.newHttpClient(); }

    @Test
    void chainWritesAreForbiddenWithoutEntityManageAndSourceSync() throws Exception {
        String deniedEntity = UUID.randomUUID().toString();
        String entity = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"entityId\":\"" + deniedEntity
            + "\",\"model\":{\"id\":\"builtin.host\",\"revision\":1},\"name\":\"denied\",\"lifecycle\":\"ACTIVE\",\"attributes\":{\"hostname\":\"denied\"}}";
        String relation = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"relationType\":\"custom.owst06_link\",\"relationRevision\":1,\"fromEntityId\":\""
            + deniedEntity + "\",\"toEntityId\":\"" + UUID.randomUUID() + "\",\"validFrom\":\"2026-10-01T00:00:00Z\",\"expectedFromVersion\":1,\"expectedToVersion\":1}";
        String link = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"namespace\":\"workflow.owst06_rel_out\",\"objectType\":\"entity\",\"externalKey\":\"ext-denied\",\"targetId\":\"" + UUID.randomUUID() + "\"}";
        String extension = "{\"base\":{\"id\":\"builtin.host\",\"revision\":1},\"fields\":[{\"id\":\"owner_team\",\"label\":\"Owner team\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":32}],\"expectedEditVersion\":0}";
        String modelDraft = "{\"definition\":{\"schemaVersion\":\"1.0\",\"id\":\"custom.owst06_denied\",\"revision\":1,\"kind\":\"ENTITY\",\"label\":\"Denied\",\"description\":\"OW-ST06 negative\",\"cleaningProfile\":\"safe-scalars-v1\",\"fields\":[{\"id\":\"biz_name\",\"label\":\"Name\",\"type\":\"TEXT\",\"required\":true,\"maxLength\":64}]},\"expectedEditVersion\":0}";
        String execute = "{\"id\":\"owst06_rel_out\",\"revision\":1,\"digest\":\"sha256:" + "0".repeat(64) + "\",\"settings\":{\"identityField\":\"raw_from\",\"nameField\":\"from_ref\"},\"previewId\":\"" + UUID.randomUUID() + "\",\"samples\":[{\"raw_from\":\"a\",\"raw_to\":\"b\"}]}";
        assertEquals(403, call("/api/v1/entities", entity).statusCode());
        assertEquals(403, call("/api/v1/entities/" + deniedEntity + "/relations", relation).statusCode());
        assertEquals(403, call("/api/v1/external-links", link).statusCode());
        assertEquals(403, call("/api/v1/catalog/drafts", modelDraft).statusCode());
        assertEquals(403, call("/api/v1/catalog/extensions/drafts", extension).statusCode());
        assertEquals(403, call("/api/v1/catalog/extensions/publish", "{\"base\":{\"id\":\"builtin.host\",\"revision\":1},\"expectedEditVersion\":0,\"digest\":\"sha256:" + "0".repeat(64) + "\"}").statusCode());
        assertEquals(403, call("/api/v1/integrations/workflows/drafts", "{\"definition\":{},\"layout\":{},\"expectedEditVersion\":0}").statusCode());
        assertEquals(403, call("/api/v1/integrations/workflows/runtime/execute", execute).statusCode());
    }

    @Test
    void grantedReadsStillAnswerExplicitly() throws Exception {
        assertEquals(200, call("/api/v1/entities", null).statusCode());
        var anonymous = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entities")).timeout(Duration.ofSeconds(15)).GET().build();
        assertEquals(401, http().send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
