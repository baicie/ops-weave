package com.acme.opsweave.platform.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.InsightSubmission;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.aicontrol.domain.ToolWindow;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production diagnosis seam end to end at the HTTP boundary: the executor dispatches the claimed
 * run's fixed content to the Agent Runtime's synchronous diagnosis call with the calling credential
 * only. The upstream here is an explicit loopback fixture; the real chain is the M4 synchronous
 * diagnosis, whose own behaviour is covered by its own tests.
 */
class AiRunRuntimeDispatchTest {
    private static final TenantId TENANT = new TenantId("tenant-test");

    private static OpsweaveProperties properties() {
        return new OpsweaveProperties(new OpsweaveProperties.Auth("dev", true, null), null, null);
    }

    private static AiRun.Run run(SubjectId owner) {
        var window = new ToolWindow(Instant.parse("2026-10-13T01:00:00Z"), Instant.parse("2026-10-13T01:30:00Z"));
        return new AiRun.Run(UUID.randomUUID(), TENANT, owner, UUID.randomUUID(), "sha256:" + "b".repeat(64), UUID.randomUUID(),
            "Summarize the incident", window, "current",
            new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64)),
            AiRun.Status.RUNNING, 1, Instant.parse("2026-10-13T01:00:01Z"), Instant.parse("2026-10-13T01:00:01Z"));
    }

    private static Principal principal(SubjectId owner) {
        return new Principal(owner, TENANT, Set.of(Permission.AI_DIAGNOSE), ResourceScope.tenantWide());
    }

    @Test
    void dispatchesTheFixedRunContentWithTheCallingCredentialOnly() throws Exception {
        var owner = new SubjectId("reader");
        var run = run(owner);
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/current-diagnoses", exchange -> {
            assertEquals("Bearer local-test", exchange.getRequestHeaders().getFirst("Authorization"));
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var runtime = new RuntimeDispatcher(properties(), "http://127.0.0.1:" + server.getAddress().getPort())) {
            new RuntimeAiRunDispatch(runtime, "local-test").dispatch(run, principal(owner));
        } finally {
            server.stop(0);
        }
        var body = JsonMapper.builder().build().readTree(seen.get());
        assertEquals(Set.of("runId", "incidentId", "question", "timeRange", "knowledgeMode"),
            body.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(run.runId().toString(), body.get("runId").asString());
        assertEquals(run.incidentId().toString(), body.get("incidentId").asString());
        assertEquals(run.question(), body.get("question").asString());
        assertEquals(run.window().from().toString(), body.get("timeRange").get("from").asString());
        assertEquals(run.window().to().toString(), body.get("timeRange").get("to").asString());
        assertEquals("current", body.get("knowledgeMode").asString());
    }

    @Test
    void refusesToRunAsADifferentSubjectAndMapsUpstreamFailures() throws Exception {
        var owner = new SubjectId("reader");
        var run = run(owner);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/current-diagnoses", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var runtime = new RuntimeDispatcher(properties(), "http://127.0.0.1:" + server.getAddress().getPort())) {
            var dispatch = new RuntimeAiRunDispatch(runtime, "local-test");
            var stranger = new Principal(new SubjectId("someone-else"), TENANT, Set.of(Permission.AI_DIAGNOSE),
                ResourceScope.tenantWide());
            assertEquals(ToolFailure.Code.FORBIDDEN, assertThrows(ToolFailure.class, () -> dispatch.dispatch(run, stranger)).code());
            assertEquals(ToolFailure.Code.UNAVAILABLE, assertThrows(ToolFailure.class,
                () -> dispatch.dispatch(run, principal(owner))).code());
        } finally {
            server.stop(0);
        }
    }
}
