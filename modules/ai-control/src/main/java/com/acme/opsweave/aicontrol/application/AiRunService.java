package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.incident.application.IncidentService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The controlled run registry use cases (ADR-104 5a): idempotent submission plus read-only readback.
 *
 * <p>Nothing here executes a run. Submitting records an admitted request in {@code PENDING} and
 * performs zero model calls; reading never changes state. Identity is always taken from the trusted
 * principal, and every read re-checks the caller's current authority over the incident it names.
 */
public final class AiRunService {
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final AiRunStore store;
    private final Clock clock;

    public AiRunService(AuthorizationService authorization, IncidentService incidents, AiRunStore store, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.incidents = Objects.requireNonNull(incidents);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Idempotent submission. A replay returns the same run; changed content is an explicit conflict. */
    public AiRun.Run submit(Principal p, AiRun.Command command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(command, "command");
        if (!p.has(Permission.AI_DIAGNOSE)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        if (authorization.authorize(p, ResourceRef.incident(p.tenantId(), command.incidentId()), Permission.AI_DIAGNOSE).denied()) {
            throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        }
        // The incident must be readable now; a missing or withdrawn incident is not a runnable target.
        incidents.get(p, command.incidentId());
        Instant now = now();
        if (command.window().to().isAfter(now)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        var candidate = new AiRun.Run(UUID.randomUUID(), p.tenantId(), p.subjectId(), command.requestId(),
            command.commandDigest(), command.incidentId(), command.question(), command.window(), command.knowledgeMode(),
            command.skill(), AiRun.Status.PENDING, 0, now, now);
        var stored = store.create(candidate);
        if (!stored.subjectId().equals(p.subjectId())) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        stored.require(command);
        return stored;
    }

    /** Read one run with fresh incident authority. Reading never executes or advances a run. */
    public AiRun.Run get(Principal p, UUID runId) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(runId, "runId");
        if (!p.has(Permission.AI_INSIGHT_READ)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        var run = store.find(p.tenantId(), runId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        if (authorization.authorize(p, new ResourceRef(p.tenantId(), "ai-run", runId.toString()), Permission.AI_INSIGHT_READ).denied()) {
            throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        }
        incidents.get(p, run.incidentId());
        return run;
    }

    /** The caller's own runs, newest first. Metadata only: no evidence, no result body. */
    public AiRun.Page list(Principal p, String after, Integer limit) {
        Objects.requireNonNull(p, "principal");
        if (!p.has(Permission.AI_INSIGHT_READ)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        String cursor = after == null ? null : AiRun.cursor(after);
        List<AiRun.Run> rows = store.page(p.tenantId(), p.subjectId(), cursor, size + 1);
        return AiRun.Page.of(rows, size);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
