package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.AiInsightStore;
import com.acme.opsweave.aicontrol.api.AiRunRuntimeStateStore;
import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.api.ModelSpendStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunCrossStore;
import com.acme.opsweave.aicontrol.domain.AiRunRuntimeState;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The cross-store seams of M5 5d: the Runtime's idempotent state delivery, the disclose-only
 * reconciliation against the run registry and the bounded observability aggregate.
 *
 * <p><b>Delivery is at-least-once and idempotent.</b> A state report is a receipt projection of the
 * Runtime's internal progress: it is recorded (bounded per run, older receipts dropped and counted)
 * and it never moves the run, never executes anything and never grants anything. A replay answers
 * with the original receipt; the request id shares the one idempotency namespace of submissions and
 * control commands, so reusing it across families is an explicit conflict. Exactly-once is never
 * claimed — a duplicate delivery is absorbed by the request id.
 *
 * <p><b>Reconciliation only discloses.</b> The comparison of registry state against the last
 * delivered Runtime state produces the closed finding list; nothing is repaired, rewritten, started
 * or stopped because of it, and recovery stays the 5b retry entry (re-authorization, fencing, no
 * second run). Reading the report or the aggregate executes nothing.
 */
public final class AiRunRuntimeStateService {
    private final AiRunStore runs;
    private final AiRunRuntimeStateStore states;
    private final AiInsightStore insights;
    private final ModelSpendStore spend;
    private final Clock clock;

    public AiRunRuntimeStateService(AiRunStore runs, AiRunRuntimeStateStore states, AiInsightStore insights,
            ModelSpendStore spend, Clock clock) {
        this.runs = Objects.requireNonNull(runs);
        this.states = Objects.requireNonNull(states);
        this.insights = Objects.requireNonNull(insights);
        this.spend = Objects.requireNonNull(spend);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Idempotent ingest of one Runtime state attestation. The run must be the caller's own and the
     * report must name it; anything else fails closed. The receipt is the only durable effect.
     */
    public AiRunRuntimeState.Receipt report(Principal p, UUID runId, AiRunRuntimeState.Report report) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(report, "report");
        if (!runId.equals(report.runId())) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        loadOwn(p, runId);
        var stored = states.find(p.tenantId(), report.requestId());
        if (stored.isPresent()) {
            stored.get().require(report);
            return AiRunRuntimeState.Receipt.of(stored.get());
        }
        // One idempotency namespace across submissions and control commands: cross-family reuse is 409.
        if (runs.byRequest(p.tenantId(), report.requestId()).isPresent()
            || runs.findCancel(p.tenantId(), report.requestId()).isPresent()
            || runs.findRetry(p.tenantId(), report.requestId()).isPresent()) {
            throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        }
        var saved = states.save(p.tenantId(), report);
        return AiRunRuntimeState.Receipt.of(saved);
    }

    /**
     * The cross-store comparison of one run: the registry state against the last delivered Runtime
     * state. Pure read: no state is written, repaired or advanced. A run without any delivered
     * attestation is disclosed as {@code MISSING_RUNTIME_STATE}, never assumed healthy.
     */
    public AiRunCrossStore.Report reconcile(AiRun.Run run) {
        Objects.requireNonNull(run, "run");
        return AiRunCrossStore.compare(run.runId(), now(), AiRunCrossStore.PlatformView.of(run),
            runtimeView(run.tenantId(), run.runId()).orElse(null), 0);
    }

    /**
     * The periodic/triggered reconciliation pass over one run: the same disclose-only comparison,
     * recorded as the run's current report snapshot so divergence is durably visible even without a
     * reader. The snapshot is an observation — replacing it with a newer comparison changes no run —
     * and consistent runs only re-record when the previous snapshot still carried findings.
     */
    public AiRunCrossStore.Report reconcileAndRecord(AiRun.Run run) {
        var report = reconcile(run);
        var previous = states.findReport(run.tenantId(), run.runId());
        if (!report.findings().isEmpty() || previous.isEmpty() || !previous.get().findings().isEmpty()) {
            states.saveReport(run.tenantId(), report);
        }
        return report;
    }

    /**
     * The bounded periodic pass: at most {@code limit} of the owner's newest runs. It reads and
     * records disclosure snapshots only — no execution, no state moves, no double runs.
     */
    public List<AiRunCrossStore.Report> reconcileRecent(Principal p, int limit) {
        Objects.requireNonNull(p, "principal");
        if (limit < 1 || limit > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        var reports = new ArrayList<AiRunCrossStore.Report>();
        for (var run : runs.page(p.tenantId(), p.subjectId(), null, limit)) {
            reports.add(reconcileAndRecord(run));
        }
        return List.copyOf(reports);
    }

    /** The last recorded cross-store report of one run; empty when no comparison was recorded. */
    public Optional<AiRunCrossStore.Report> recorded(AiRun.Run run) {
        return states.findReport(run.tenantId(), run.runId());
    }

    /**
     * The bounded read-only aggregate of one run: event retention with its truncation counters, the
     * model fee counters and the delivered Runtime state reports. Nothing executes because it is
     * read and nothing runs in the background for it.
     */
    public AiRunRuntimeState.Observability observability(AiRun.Run run) {
        Objects.requireNonNull(run, "run");
        var retention = runs.retention(run.tenantId(), run.runId());
        var fees = AiRunExecutionService.reconcile(insights, spend, run.tenantId(), run.runId(), now());
        var counts = states.counts(run.tenantId(), run.runId());
        var latest = states.latest(run.tenantId(), run.runId());
        return new AiRunRuntimeState.Observability(run.runId(), run.status().name(), run.statusVersion(), run.attempt(),
            new AiRunRuntimeState.Observability.Events(retention.window(), retention.retained(),
                retention.dropped(), retention.truncated()),
            new AiRunRuntimeState.Observability.Spend(fees.priorModelCalls(), fees.uncertainPriorFees()),
            new AiRunRuntimeState.Observability.Runtime(counts.retained(), counts.dropped(),
                latest.map(AiRunRuntimeState.Report::attempt).orElse(null),
                latest.map(AiRunRuntimeState.Report::reportedAt).orElse(null)));
    }

    private Optional<AiRunCrossStore.RuntimeView> runtimeView(TenantId tenant, UUID runId) {
        return states.latest(tenant, runId).map(report -> new AiRunCrossStore.RuntimeView(report.status(),
            report.attempt(), report.stepCount(), report.checkpointSeq(), report.reportedAt()));
    }

    /** The caller's own run, visible now. Another owner's run is never a guessed 403 but a 404. */
    private AiRun.Run loadOwn(Principal p, UUID runId) {
        var run = runs.find(p.tenantId(), runId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        if (!run.subjectId().equals(p.subjectId())) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        return run;
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
