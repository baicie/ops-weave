package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.platform.catalog.CatalogJson;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Actual local HTTP and PostgreSQL for the run event channel (ADR-104 §2.5): the cursor readback and
 * the SSE push transport of the same persisted records. The run reaches its terminal state through
 * the real executor (the Runtime port is not configured, so the dispatch fails closed and the run
 * lands FAILED with its reason) and the event log is then fixed. A stream that is disconnected and
 * resumed from its cursor delivers every record exactly once and reading executes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=ai-run-event-http-fixture-only-32-characters",
    "opsweave.auth.dev.subject=ai-run-event-author",
    "opsweave.auth.dev.permissions=entity.read,metric.read,incident.read,evidence.read,ai.diagnose,ai.insight.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=postgres"})
class AiRunEventHttpIT {
    static final String TOKEN = "ai-run-event-http-fixture-only-32-characters";
    static final String TENANT = "ai-run-event-http-" + UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("opsweave.auth.dev.tenant", () -> TENANT);
        registry.add("opsweave.inventory.jdbc-url", () -> System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        registry.add("opsweave.inventory.jdbc-user", () -> System.getenv("OPSWEAVE_TEST_JDBC_USER"));
        registry.add("opsweave.inventory.jdbc-password", () -> System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    @LocalServerPort
    int port;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> raw(String path, String body, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> call(String path) throws Exception {
        return raw(path, null, true);
    }

    static JsonNode json(String body) {
        return CatalogJson.JSON.readTree(body);
    }

    static JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return json(response.body());
    }

    static List<JsonNode> items(JsonNode page) {
        var items = new ArrayList<JsonNode>();
        page.get("items").forEach(items::add);
        return items;
    }

    /** A run of the fixture incident whose diagnosis lands through the real executor. */
    String submitRun() throws Exception {
        raw("/api/v1/integrations/zabbix/hosts/sync", null, true);
        var query = new LinkedHashMap<String, Object>();
        query.put("from", Instant.parse("2026-09-21T11:59:00Z").getEpochSecond());
        query.put("till", Instant.parse("2026-09-21T12:59:00Z").getEpochSecond());
        query.put("limit", 25);
        query.put("afterEventId", null);
        ok(raw("/api/v1/integrations/zabbix/problems/ingest", CatalogJson.JSON.writeValueAsString(query), true));
        String incidentId = ok(call("/api/v1/incidents?limit=1")).get("items").get(0).get("id").asString();
        var to = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        var command = new LinkedHashMap<String, Object>();
        command.put("requestId", UUID.randomUUID().toString());
        command.put("incidentId", incidentId);
        command.put("question", "Which change explains the latency spike in this window?");
        command.put("timeRange", Map.of("from", to.minusSeconds(1800).toString(), "to", to.toString()));
        command.put("knowledgeMode", "current");
        command.put("skill", Map.of("id", "incident.diagnose", "version", "2.0.0", "digest", "sha256:" + "a".repeat(64)));
        String runId = ok(raw("/api/v1/ai/runs", CatalogJson.JSON.writeValueAsString(command), true)).get("runId").asString();
        // The executor claims and lands the run through the real lifecycle; wait for the terminal state.
        for (int i = 0; i < 120; i++) {
            var run = ok(call("/api/v1/ai/runs/" + runId));
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(run.get("status").asString())) return runId;
            Thread.sleep(250);
        }
        throw new AssertionError("the run did not land");
    }

    @Test
    void theRunLifecycleIsRecordedBehindACursorAndReadingExecutesNothing() throws Exception {
        String runId = submitRun();
        var before = call("/api/v1/ai/runs/" + runId);
        var run = ok(before);
        var page = ok(call("/api/v1/ai/runs/" + runId + "/events?limit=50"));
        assertEquals(runId, page.get("runId").asString());
        assertEquals(run.get("status").asString(), page.get("snapshot").get("status").asString());
        assertEquals(run.get("statusVersion").asLong(), page.get("snapshot").get("statusVersion").asLong());
        assertFalse(page.get("gap").asBoolean());
        assertFalse(page.get("hasMore").asBoolean());
        assertTrue(page.get("nextCursor").isNull());
        var records = items(page);
        assertEquals(List.of("SUBMITTED", "CLAIMED", "RECONCILED", "TOOL_WAIT", "STATUS_CHANGED", "FINISHED"),
            records.stream().map(item -> item.get("type").asString()).toList());
        for (int i = 0; i < records.size(); i++) {
            assertEquals(i + 1, records.get(i).get("seq").asLong());
            assertEquals(1, records.get(i).get("payloadVersion").asInt());
            assertEquals(runId, records.get(i).get("runId").asString());
        }
        assertEquals("FAILED", records.get(5).get("payload").get("status").asString());
        assertEquals("RUNTIME_UNAVAILABLE", records.get(5).get("payload").get("reason").asString());
        // Bounded pages cover every record exactly once and the cursor continues strictly after it.
        var seen = new ArrayList<Long>();
        String after = null;
        boolean more = true;
        while (more) {
            var bounded = ok(call("/api/v1/ai/runs/" + runId + "/events?limit=2" + (after == null ? "" : "&afterSeq=" + after)));
            for (var item : items(bounded)) seen.add(item.get("seq").asLong());
            more = bounded.get("hasMore").asBoolean();
            assertEquals(more, !bounded.get("nextCursor").isNull());
            after = bounded.get("nextCursor").isNull() ? null : bounded.get("nextCursor").asString();
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), seen);
        // Replaying a cursor re-delivers the same records and nothing moves.
        var replay = call("/api/v1/ai/runs/" + runId + "/events?afterSeq=3&limit=2");
        assertEquals(200, replay.statusCode());
        assertEquals(replay.body(), call("/api/v1/ai/runs/" + runId + "/events?afterSeq=3&limit=2").body());
        assertEquals(before.body(), call("/api/v1/ai/runs/" + runId).body());
    }

    @Test
    void thePushChannelResumesFromTheCursorAfterADisconnect() throws Exception {
        String runId = submitRun();
        var expected = items(ok(call("/api/v1/ai/runs/" + runId + "/events?limit=50")))
            .stream().map(item -> item.get("seq").asLong()).toList();
        var before = call("/api/v1/ai/runs/" + runId).body();
        // First connection: the reader goes away after the first delivered frame.
        var first = new ArrayList<Long>();
        try (var stream = open(runId, null, null)) {
            var frame = stream.next();
            assertNotNull(frame);
            assertEquals(runId, frame.get("runId").asString());
            frame.get("items").forEach(item -> first.add(item.get("seq").asLong()));
        }
        assertEquals(expected, first);
        // Reconnecting from the last delivered position delivers nothing new and just ends.
        var resumed = new ArrayList<Long>();
        try (var stream = open(runId, first.getLast(), null)) {
            JsonNode frame;
            while ((frame = stream.next()) != null) frame.get("items").forEach(item -> resumed.add(item.get("seq").asLong()));
        }
        assertTrue(resumed.isEmpty());
        // Resuming from an earlier position (a client that lost part of a batch) re-delivers exactly
        // the later records, and the standard SSE header resumes in the same way.
        var replayed = new ArrayList<Long>();
        try (var stream = open(runId, 2L, null)) {
            JsonNode frame;
            while ((frame = stream.next()) != null) frame.get("items").forEach(item -> replayed.add(item.get("seq").asLong()));
        }
        assertEquals(expected.subList(2, expected.size()), replayed);
        var header = new ArrayList<Long>();
        try (var stream = open(runId, null, "2")) {
            JsonNode frame;
            while ((frame = stream.next()) != null) frame.get("items").forEach(item -> header.add(item.get("seq").asLong()));
        }
        assertEquals(expected.subList(2, expected.size()), header);
        // Delivering and re-reading records executes nothing: the run stands exactly where it stood.
        assertEquals(before, call("/api/v1/ai/runs/" + runId).body());
    }

    @Test
    void eventReadbackAndStreamKeepTheirClosedBoundaries() throws Exception {
        String runId = submitRun();
        // The run read boundary is the event read boundary.
        assertEquals(401, raw("/api/v1/ai/runs/" + runId + "/events", null, false).statusCode());
        assertEquals(401, streamStatus(runId, "", null, false).statusCode());
        assertEquals(404, call("/api/v1/ai/runs/" + UUID.randomUUID() + "/events").statusCode());
        var missing = streamStatus(UUID.randomUUID().toString(), "", null, true);
        assertEquals(404, missing.statusCode(), missing.body());
        // Closed query shape: unknown parameters, illegal cursors and disagreeing resume positions are 400.
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?afterSeq=x").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?afterSeq=-1").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?afterSeq=01").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?afterSeq=0&afterSeq=1").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?limit=0").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?limit=51").statusCode());
        assertEquals(400, call("/api/v1/ai/runs/" + runId + "/events?tenantId=other").statusCode());
        assertEquals(400, streamStatus(runId, "?afterSeq=x", null, true).statusCode());
        assertEquals(400, streamStatus(runId, "?afterSeq=1", "2", true).statusCode());
        assertEquals(400, streamStatus(runId, "", "not-a-cursor", true).statusCode());
        assertEquals(200, streamStatus(runId, "?afterSeq=1", "1", true).statusCode());
        // A run id that is not a UUID is an invalid request, never a guessed resource.
        assertEquals(400, call("/api/v1/ai/runs/not-a-uuid/events").statusCode());
        // The push transport announces itself as SSE and keeps responses unbuffered.
        try (var stream = open(runId, 0L, null)) {
            assertEquals("text/event-stream", stream.contentType());
            assertEquals("no-store", stream.cacheControl());
            assertNotNull(stream.next());
        }
    }

    HttpResponse<String> streamStatus(String runId, String query, String lastEventId, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/runs/" + runId + "/events/stream" + query))
            .timeout(Duration.ofSeconds(15)).header("Accept", "text/event-stream");
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A bounded SSE reader over one connection: frames are readback pages, nothing else. */
    final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;
        final BufferedReader reader;

        Stream(HttpResponse<InputStream> response) {
            this.response = response;
            this.reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        }

        String cacheControl() {
            return response.headers().firstValue("Cache-Control").orElse("");
        }

        /** The next readback page frame, or {@code null} when the channel closed. */
        JsonNode next() throws Exception {
            var data = new StringBuilder();
            while (true) {
                String line = reader.readLine();
                if (line == null) return null;
                if (line.isEmpty()) {
                    if (data.isEmpty()) continue;
                    return json(data.toString());
                }
                if (line.startsWith("data: ")) data.append(line.substring(6));
            }
        }

        @Override
        public void close() throws Exception {
            response.body().close();
        }
    }

    Stream open(String runId, Long afterSeq, String lastEventId) throws Exception {
        String suffix = afterSeq == null ? "" : "?afterSeq=" + afterSeq;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/runs/" + runId + "/events/stream" + suffix))
            .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + TOKEN)
            .header("Accept", "text/event-stream");
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return new Stream(response);
    }
}
