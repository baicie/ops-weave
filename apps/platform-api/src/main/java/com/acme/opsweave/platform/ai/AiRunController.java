package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.AiRunEventService;
import com.acme.opsweave.aicontrol.application.AiRunService;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.incident.IncidentController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Controlled entry points of the durable run registry (ADR-104 5a + 5b + 5c): idempotent submission,
 * read-only readback by id and by bounded cursor page, the cooperative cancellation and the same-run
 * recovery commands with their idempotent receipts, plus the ordered run event log — a cursor
 * readback and its bounded SSE push channel. The request body never carries identity; reading never
 * executes or advances a run and delivering an event never executes anything either.
 */
@RestController
@RequestMapping("/api/v1/ai/runs")
public final class AiRunController {
    /** Concurrent event streams per process; the channel is bounded, never an unbounded fan-out. */
    private static final Semaphore STREAMS = new Semaphore(8);

    private final PrincipalContext principals;
    private final AiRunService service;
    private final AiRunEventService events;
    private final AiRunExecution execution;

    public AiRunController(PrincipalContext principals, AuthorizationService authorization, IncidentService incidents,
            InventoryWiring wiring, AiRunExecution execution) {
        this.principals = principals;
        this.service = new AiRunService(authorization, incidents, wiring.aiRuns(), Clock.systemUTC());
        this.events = new AiRunEventService(this.service, wiring.aiRuns());
        this.execution = execution;
    }

    /** Idempotent submission: a replay answers with the same run, changed content is a conflict. */
    @PostMapping(consumes = "application/json")
    public Object submit(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var command = AiRunJson.command(CatalogJson.read(request));
        var p = principals.requirePrincipal();
        var run = service.submit(p, command);
        execution.schedule(run.runId(), p);
        return AiRunJson.wire(run);
    }

    /** Read one run with fresh authority. Reading never executes or advances a run. */
    @GetMapping("/{runId}")
    public Object get(@PathVariable String runId, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        return AiRunJson.wire(service.get(principals.requirePrincipal(), uuid(runId)));
    }

    /** The caller's own runs, newest first; the cursor continues strictly after the last item. */
    @GetMapping
    public Object list(HttpServletRequest request) {
        var params = request.getParameterMap();
        for (var name : params.keySet()) {
            if (!"limit".equals(name) && !"after".equals(name)) throw new IllegalArgumentException();
        }
        Integer limit = null;
        var limits = params.get("limit");
        if (limits != null) {
            if (limits.length != 1) throw new IllegalArgumentException();
            try {
                limit = Integer.valueOf(limits[0]);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException();
            }
        }
        String after = null;
        var afters = params.get("after");
        if (afters != null) {
            if (afters.length != 1) throw new IllegalArgumentException();
            after = afters[0];
        }
        return AiRunJson.page(service.list(principals.requirePrincipal(), after, limit));
    }

    /**
     * Idempotent cooperative cancellation: the request is recorded beside the state machine and the
     * executor lands {@code CANCELLED} at its next safe point. A replay answers with the original
     * receipt; a terminal run is idempotent and changes nothing.
     */
    @PostMapping(value = "/{runId}/cancel", consumes = "application/json")
    public Object cancel(@PathVariable String runId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var id = uuid(runId);
        var command = AiRunJson.controlCommand(CatalogJson.read(request), AiRunControl.Kind.CANCEL, id);
        return AiRunJson.wire(execution.control().cancel(principals.requirePrincipal(), id, command));
    }

    /**
     * Idempotent recovery: a new attempt on the same run after re-authorization and after the
     * unknown completion state of earlier attempts has been reconciled and explicitly disclosed.
     */
    @PostMapping(value = "/{runId}/retries", consumes = "application/json")
    public Object retry(@PathVariable String runId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var id = uuid(runId);
        var command = AiRunJson.controlCommand(CatalogJson.read(request), AiRunControl.Kind.RETRY, id);
        return AiRunJson.wire(execution.control().retry(principals.requirePrincipal(), id, command));
    }

    /**
     * Ordered event readback (ADR-104 §2.5): a persisted record log behind a cursor. Replaying or
     * re-reading the same cursor re-delivers the same records and executes nothing; a cursor below
     * the retained window has expired and the answer is the retained window plus an explicit gap and
     * the current run snapshot.
     */
    @GetMapping("/{runId}/events")
    public Object events(@PathVariable String runId, HttpServletRequest request) {
        var params = request.getParameterMap();
        for (var name : params.keySet()) {
            if (!"afterSeq".equals(name) && !"limit".equals(name)) throw new IllegalArgumentException();
        }
        Integer limit = null;
        var limits = params.get("limit");
        if (limits != null) {
            if (limits.length != 1) throw new IllegalArgumentException();
            try {
                limit = Integer.valueOf(limits[0]);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException();
            }
        }
        String afterSeq = null;
        var cursors = params.get("afterSeq");
        if (cursors != null) {
            if (cursors.length != 1) throw new IllegalArgumentException();
            afterSeq = cursors[0];
        }
        return AiRunJson.page(events.read(principals.requirePrincipal(), uuid(runId), afterSeq, limit));
    }

    /**
     * The push channel of the same cursor protocol: SSE frames carry readback pages and nothing
     * else, a reconnecting client resumes from its last sequence number (or the standard {@code
     * Last-Event-ID} header) and polling over {@code GET .../events} stays the documented fallback.
     * Every connection is bounded in time and bytes; a slow client is disconnected and resumes from
     * its own cursor instead of being buffered without limit.
     */
    @GetMapping(value = "/{runId}/events/stream", produces = "text/event-stream")
    public void stream(@PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        var params = request.getParameterMap();
        for (var name : params.keySet()) {
            if (!"afterSeq".equals(name)) throw new IllegalArgumentException();
        }
        String afterSeq = null;
        var cursors = params.get("afterSeq");
        if (cursors != null) {
            if (cursors.length != 1) throw new IllegalArgumentException();
            afterSeq = cursors[0];
        }
        long resume = resumeCursor(afterSeq, lastEventId);
        var id = uuid(runId);
        var p = principals.requirePrincipal();
        if (!STREAMS.tryAcquire()) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
        try {
            // One read before committing: authorization, cursor and store failures map to closed errors.
            events.read(p, id, Long.toString(resume), AiRunEventStream.LIMITS.frameEvents());
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            var out = response.getOutputStream();
            AiRunEventStream.pump(seq -> events.read(p, id, Long.toString(seq), AiRunEventStream.LIMITS.frameEvents()),
                new AiRunEventStream.Sink() {
                    @Override
                    public int retry(int millis) throws IOException {
                        return write(("retry: " + millis + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override
                    public int frame(Long lastSeq, String json) throws IOException {
                        var text = (lastSeq == null ? "" : "id: " + lastSeq + "\n") + "data: " + json + "\n\n";
                        return write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override
                    public int keepalive() throws IOException {
                        return write(": keepalive\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    private int write(byte[] frame) throws IOException {
                        out.write(frame);
                        out.flush();
                        return frame.length;
                    }
                }, resume, AiRunEventStream.LIMITS);
        } catch (IOException clientGone) {
            // A disconnected client costs nothing: the records are persisted and it resumes from its
            // own cursor. Nothing is executed, retried or notified because a reader left.
        } finally {
            STREAMS.release();
        }
    }

    /** The resuming position: the query parameter, else the standard header, never both disagreeing. */
    private static long resumeCursor(String afterSeq, String lastEventId) {
        String header = lastEventId == null || lastEventId.isBlank() ? null : lastEventId.trim();
        long cursor = AiRunEvent.cursor(afterSeq);
        if (afterSeq != null && header != null && cursor != AiRunEvent.cursor(header)) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        return AiRunEvent.cursor(afterSeq != null ? afterSeq : header);
    }

    private static UUID uuid(String value) {
        return IncidentController.uuid(value);
    }
}
