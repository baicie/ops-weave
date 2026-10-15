import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.application.AiRunRuntimeStateService;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.util.*;

/**
 * M5 5d domain semantics: the Runtime's closed state attestation, the bounded and idempotent
 * delivery receipts, the disclose-only cross-store reconciliation and the bounded observability
 * aggregate (event retention truncation included).
 *
 * <p>The insight store and the spend store below are <b>explicit test fixtures</b> (labelled
 * Fixture): the real fee receipts live in {@code ai_control.model_spend} and are covered by the
 * PostgreSQL IT. Nothing here calls a model or executes anything — ingestion records a receipt and
 * reconciliation only discloses; neither moves a run.
 */
public class AiRunRuntimeStateSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("AI run runtime state check " + checks); }
    static void invalid(Runnable action) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    static void conflict(String code, Runnable action) {
        checks++;
        try { action.run(); } catch (AiRun.Conflict failure) {
            if (failure.code().equals(code)) return;
            throw new AssertionError(failure.code() + " != " + code);
        }
        throw new AssertionError("Expected " + code + " " + checks);
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-14T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    /** Explicit test fixture: a saved complete result keyed by run id, nothing else. */
    static final class FixtureInsightStore implements AiInsightStore {
        final Map<String, AiInsight> saved = new HashMap<>();
        static String key(TenantId tenant, UUID runId) { return tenant.value() + "|" + runId; }
        public Optional<AiInsight> find(TenantId tenant, UUID id) { return Optional.ofNullable(saved.get(key(tenant, id))); }
        public AiInsight save(AiInsight insight) { saved.put(key(insight.tenantId(), insight.input().runId()), insight); return insight; }
    }

    /** Explicit test fixture: the fee receipts of one run as the spend store would report them. */
    static final class FixtureSpendStore implements ModelSpendStore {
        final Map<String, ModelSpend.Call> calls = new HashMap<>();
        static String key(TenantId tenant, UUID runId) { return tenant.value() + "|" + runId; }
        public ModelSpend.Call reserve(ModelSpend.Call call) { calls.put(key(call.tenantId(), call.runId()), call); return call; }
        public ModelSpend.Call report(TenantId tenant, SubjectId subject, UUID run, UUID session,
                ModelSpend.Usage usage, Instant now) {
            var call = calls.get(key(tenant, run));
            var reported = call.report(usage, now);
            calls.put(key(tenant, run), reported);
            return reported;
        }
        public Optional<ModelSpend.Call> find(TenantId tenant, UUID run) { return Optional.ofNullable(calls.get(key(tenant, run))); }
        public List<ModelSpend.Metric> metrics(TenantId tenant, ModelSpend.MetricsQuery query, Instant now) { return List.of(); }
    }

    record Fixture(TenantId tenant, Principal owner, Principal other, TestClock clock, InMemoryAiRunStore runs,
                   InMemoryAiRunRuntimeStateStore states, FixtureInsightStore insights, FixtureSpendStore spend,
                   AiRunRuntimeStateService service, AiRun.Run run) {}

    static Fixture fixture() {
        return fixture(new InMemoryAiRunStore());
    }

    static Fixture fixture(InMemoryAiRunStore runs) {
        var tenant = new TenantId("tenant-demo");
        var clock = new TestClock();
        var owner = new Principal(new SubjectId("owner"), tenant, Set.of(Permission.AI_DIAGNOSE),
            ResourceScope.tenantWide());
        var other = new Principal(new SubjectId("someone-else"), tenant, Set.of(Permission.AI_DIAGNOSE),
            ResourceScope.tenantWide());
        var states = new InMemoryAiRunRuntimeStateStore();
        var insights = new FixtureInsightStore();
        var spend = new FixtureSpendStore();
        var service = new AiRunRuntimeStateService(runs, states, insights, spend, clock);
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-14T00:30:00Z"));
        var command = new AiRun.Command(UUID.randomUUID(), UUID.randomUUID(), "Why is latency high?", window,
            "current", skill);
        var run = runs.create(new AiRun.Run(UUID.randomUUID(), tenant, owner.subjectId(), command.requestId(),
            command.commandDigest(), command.incidentId(), command.question(), command.window(),
            command.knowledgeMode(), command.skill(), AiRun.Status.PENDING, 0,
            Instant.parse("2026-10-14T00:59:00Z"), Instant.parse("2026-10-14T00:59:00Z")));
        return new Fixture(tenant, owner, other, clock, runs, states, insights, spend, service, run);
    }

    static AiRunRuntimeState.Report report(Fixture f, String requestId, int attempt, String status, Instant at) {
        return new AiRunRuntimeState.Report(UUID.fromString(requestId), f.run().runId(), attempt, status,
            2, 1, 1, at);
    }

    public static void main(String[] args) {
        var f = fixture();

        // The attestation is closed: counters and status only, all bounds explicit.
        invalid(() -> report(f, "10000000-0000-4000-8000-0000000000a1", 1, "QUEUED", f.clock().instant()));
        invalid(() -> report(f, "10000000-0000-4000-8000-0000000000a1", 0, "RUNNING", f.clock().instant()));
        invalid(() -> new AiRunRuntimeState.Report(UUID.randomUUID(), f.run().runId(), 1, "RUNNING",
            1_001, 1, 1, f.clock().instant()));
        invalid(() -> new AiRunRuntimeState.Report(UUID.randomUUID(), f.run().runId(), 1, "RUNNING",
            2, -1, 1, f.clock().instant()));
        invalid(() -> new AiRunRuntimeState.Report(UUID.randomUUID(), f.run().runId(), 1, "RUNNING",
            2, 1, 1_001, f.clock().instant()));
        check(report(f, "10000000-0000-4000-8000-0000000000a1", 1, "RUNNING", f.clock().instant()).attempt() == 1);

        // Idempotent delivery: a replay answers with the original receipt, changed content conflicts.
        var first = report(f, "10000000-0000-4000-8000-0000000000a1", 1, "RUNNING", f.clock().instant());
        var receipt = f.service().report(f.owner(), f.run().runId(), first);
        check(receipt.requestId().equals(first.requestId()));
        check(receipt.status().equals("RUNNING"));
        var replay = f.service().report(f.owner(), f.run().runId(), first);
        check(replay.equals(receipt));
        conflict("AI_RUN_REQUEST_REUSED", () -> f.service().report(f.owner(), f.run().runId(),
            report(f, "10000000-0000-4000-8000-0000000000a1", 2, "RUNNING", f.clock().instant())));
        // The request id shares the one idempotency namespace: submissions and control commands collide.
        conflict("AI_RUN_REQUEST_REUSED", () -> f.service().report(f.owner(), f.run().runId(),
            report(f, f.run().requestId().toString(), 1, "RUNNING", f.clock().instant())));
        // The report must name the run in the path, and only the owner's run is visible.
        invalid(() -> f.service().report(f.owner(), f.run().runId(),
            new AiRunRuntimeState.Report(UUID.randomUUID(), UUID.randomUUID(), 1, "RUNNING", 2, 1, 1,
                f.clock().instant())));
        checks++;
        try {
            f.service().report(f.other(), f.run().runId(), report(f, "10000000-0000-4000-8000-0000000000a2", 1,
                "RUNNING", f.clock().instant()));
            throw new AssertionError("Expected NOT_FOUND " + checks);
        } catch (ToolFailure expected) {
            if (expected.code() != ToolFailure.Code.NOT_FOUND) throw new AssertionError(expected.code().name());
        }
        // Delivery is a receipt projection only: the run state machine did not move at all.
        check(f.runs().find(f.tenant(), f.run().runId()).orElseThrow().statusVersion() == 0);
        check(f.runs().find(f.tenant(), f.run().runId()).orElseThrow().status() == AiRun.Status.PENDING);
        check(f.runs().read(f.tenant(), f.run().runId(), 0, 50).size() == 1);

        // The bounded receipt window: older attestations are dropped and the drop is counted.
        for (int i = 0; i < 60; i++) {
            f.service().report(f.owner(), f.run().runId(),
                report(f, String.format("10000000-0000-4000-8000-00000000%04x", 0x0100 + i), 1, "RUNNING",
                    f.clock().instant()));
        }
        var counts = f.states().counts(f.tenant(), f.run().runId());
        check(counts.retained() == AiRunRuntimeState.RETAINED_REPORTS);
        check(counts.dropped() >= 10);
        check(f.states().find(f.tenant(), UUID.fromString("10000000-0000-4000-8000-000000000100")).isEmpty());

        // Cross-store comparison: every divergence is disclosed and nothing is repaired.
        var platform = new AiRunCrossStore.PlatformView("RUNNING", 3, 1, f.clock().instant());
        var runtime = new AiRunCrossStore.RuntimeView("RUNNING", 1, 2, 1, f.clock().instant());
        check(AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform, runtime, 0).consistent());
        check(AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform, runtime, 0).repair().equals("none"));
        var terminal = new AiRunCrossStore.PlatformView("SUCCEEDED", 4, 1, f.clock().instant().plusSeconds(60));
        var diverging = AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), terminal, runtime, 0);
        check(diverging.findings().equals(List.of(AiRunCrossStore.Finding.PLATFORM_TERMINAL_RUNTIME_ACTIVE,
            AiRunCrossStore.Finding.STALE_RUNTIME_REPORT)));
        var reverse = AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform,
            new AiRunCrossStore.RuntimeView("FAILED", 1, 2, 1, f.clock().instant()), 0);
        check(reverse.findings().equals(List.of(AiRunCrossStore.Finding.RUNTIME_TERMINAL_PLATFORM_ACTIVE)));
        var mismatch = AiRunCrossStore.compare(f.run().runId(), f.clock().instant(),
            new AiRunCrossStore.PlatformView("WAITING_TOOL", 3, 2, f.clock().instant()), runtime, 0);
        check(mismatch.findings().equals(List.of(AiRunCrossStore.Finding.STATUS_MISMATCH,
            AiRunCrossStore.Finding.ATTEMPT_MISMATCH)));
        check(AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform, null, 0).findings()
            .equals(List.of(AiRunCrossStore.Finding.MISSING_RUNTIME_STATE)));
        check(AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), null, runtime, 0).findings()
            .equals(List.of(AiRunCrossStore.Finding.MISSING_ON_PLATFORM)));
        check(AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform, runtime, 2).findings()
            .equals(List.of(AiRunCrossStore.Finding.UNDELIVERED_STATE_REPORT)));

        // An unsound, invented, duplicated or unordered finding list is a construction error.
        invalid(() -> new AiRunCrossStore.Report(f.run().runId(), f.clock().instant(), platform, runtime,
            List.of(AiRunCrossStore.Finding.STATUS_MISMATCH)));
        invalid(() -> new AiRunCrossStore.Report(f.run().runId(), f.clock().instant(), platform, runtime,
            List.of(AiRunCrossStore.Finding.ATTEMPT_MISMATCH, AiRunCrossStore.Finding.STATUS_MISMATCH)));
        invalid(() -> new AiRunCrossStore.Report(f.run().runId(), f.clock().instant(), platform, runtime,
            List.of(AiRunCrossStore.Finding.STATUS_MISMATCH, AiRunCrossStore.Finding.STATUS_MISMATCH)));
        invalid(() -> new AiRunCrossStore.Report(f.run().runId(), f.clock().instant(), null, null, List.of()));
        invalid(() -> AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), null, null, 0));
        invalid(() -> AiRunCrossStore.compare(f.run().runId(), f.clock().instant(), platform, runtime, -1));

        // Reading the report discloses only: the run is byte-for-byte where it was.
        var before = f.runs().find(f.tenant(), f.run().runId()).orElseThrow();
        var report = f.service().reconcile(before);
        check(report.findings().contains(AiRunCrossStore.Finding.PLATFORM_TERMINAL_RUNTIME_ACTIVE)
            || report.findings().contains(AiRunCrossStore.Finding.STATUS_MISMATCH)
            || report.findings().contains(AiRunCrossStore.Finding.ATTEMPT_MISMATCH)
            || report.findings().contains(AiRunCrossStore.Finding.STALE_RUNTIME_REPORT)
            || report.findings().isEmpty());
        check(f.runs().find(f.tenant(), f.run().runId()).orElseThrow().equals(before));
        check(f.runs().read(f.tenant(), f.run().runId(), 0, 50).size() == 1);
        check(report.repair().equals("none"));

        // The periodic pass records the disclosure snapshot; consistent runs only record once.
        var recorded = f.service().reconcileAndRecord(before);
        check(f.service().recorded(before).isPresent());
        check(f.service().recorded(before).orElseThrow().equals(recorded));
        var again = f.service().reconcileAndRecord(before);
        check(again.findings().equals(recorded.findings()));
        check(f.states().findReport(f.tenant(), before.runId()).isPresent());

        // The observability aggregate: bounded, read-only, with honest truncation counters.
        var obs = f.service().observability(before);
        check(obs.runId().equals(before.runId()));
        check(obs.events().window() == AiRunEvent.RETAINED_EVENTS);
        check(obs.events().dropped() == 0);
        check(!obs.events().truncated());
        check(obs.runtime().deliveredReports() == counts.retained());
        check(obs.runtime().latestAttempt() == 1);
        check(obs.spend().priorModelCalls() == 0);
        check(obs.spend().uncertainPriorFees() == 0);
        // A retained fee without a result is an uncertain fee: counted, never hidden or refunded.
        f.spend().reserve(new ModelSpend.Call(f.tenant(), f.owner().subjectId(), before.runId(), UUID.randomUUID(),
            before.incidentId(), "sha256:" + "a".repeat(64), 10, ModelSpend.Policy.mock(),
            f.clock().instant(), f.clock().instant().plusSeconds(20), null, null));
        var billed = f.service().observability(before);
        check(billed.spend().priorModelCalls() == 1);
        check(billed.spend().uncertainPriorFees() == 1);

        // The truncated event window is disclosed, never presented as a complete history.
        var small = fixture(new InMemoryAiRunStore(3));
        var claim = small.runs().claim(small.tenant(), small.run().runId(), "worker", small.clock().instant(),
            Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        small.runs().advance(small.tenant(), small.run().runId(), claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, small.clock().instant()));
        small.runs().advance(small.tenant(), small.run().runId(), claim.run().statusVersion() + 1, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, small.clock().instant()));
        small.runs().advance(small.tenant(), small.run().runId(), claim.run().statusVersion() + 2, claim.fence(),
            AiRun.Transition.to(AiRun.Status.SUCCEEDED, small.clock().instant()));
        var truncated = small.service().observability(small.runs().find(small.tenant(), small.run().runId()).orElseThrow());
        check(truncated.events().window() == 3);
        check(truncated.events().truncated());
        check(truncated.events().dropped() > 0);
        check(truncated.events().retained() <= 3);

        // The aggregate closes: truncation flags and counters say the same thing, nothing else.
        invalid(() -> new AiRunRuntimeState.Observability.Events(200, 7, 0, true));
        invalid(() -> new AiRunRuntimeState.Observability.Events(200, 7, 3, false));
        invalid(() -> new AiRunRuntimeState.Observability.Events(200, 201, 0, false));
        invalid(() -> new AiRunRuntimeState.Observability.Spend(0, 1));
        invalid(() -> new AiRunRuntimeState.Observability.Runtime(0, 0, 1, f.clock().instant()));
        invalid(() -> new AiRunRuntimeState.Observability.Runtime(2, 0, null, f.clock().instant()));
        invalid(() -> new AiRunRuntimeState.Observability.Runtime(2, 0, 1, null));
        check(new AiRunRuntimeState.Observability.Runtime(2, 1, 1, f.clock().instant()).deliveredReports() == 2);

        System.out.println("AiRunRuntimeStateSmoke: " + checks + " checks passed");
    }
}
