import com.acme.opsweave.aicontrol.application.AiRunService;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.InMemoryAiRunStore;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.util.*;

/**
 * Run event log semantics (ADR-104 §2.5): events are records and never commands, the type set and
 * every payload are closed, sequence numbers are per run and monotonic, an expired cursor answers
 * with the retained window plus an explicit gap and the current snapshot, and reading or replaying
 * the same cursor has zero side effects. No store I/O and no model call is involved here; the real
 * table shapes and the push channel are covered by the PostgreSQL IT.
 */
public class AiRunEventSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("AI run event check " + checks); }
    static void invalid(Runnable action) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    public static void main(String[] args) {
        // The type set is closed and the payload schema version is fixed.
        check(AiRunEvent.Type.values().length == 7);
        check(AiRunEvent.PAYLOAD_VERSION == 1);
        check(AiRunEvent.RETAINED_EVENTS == 200);
        check(new HashSet<>(Arrays.asList(AiRunEvent.Type.values())).equals(
            new HashSet<>(List.of(AiRunEvent.Type.SUBMITTED, AiRunEvent.Type.CLAIMED, AiRunEvent.Type.STATUS_CHANGED,
                AiRunEvent.Type.TOOL_WAIT, AiRunEvent.Type.CANCEL_REQUESTED, AiRunEvent.Type.RECONCILED,
                AiRunEvent.Type.FINISHED))));

        var clock = new TestClock();
        var tenant = new TenantId("ai-run-event-domain");
        var user = new SubjectId("reader");
        var p = new Principal(user, tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ, Permission.INCIDENT_READ,
            Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ), ResourceScope.tenantWide());
        var auth = new AuthorizeUseCase();
        var incidents = new InMemoryIncidentStore((t, s, h) -> Map.of());
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3,
            clock.now.minusSeconds(30), clock.now, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), clock.now);
        var incident = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(auth, incidents, clock);
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(clock.now.minusSeconds(600), clock.now.minusSeconds(300));
        var command = new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);
        var store = new InMemoryAiRunStore();
        var service = new AiRunService(auth, incidentService, store, clock);
        var run = service.submit(p, command);
        var uuid = run.runId();

        // Every factory records the fact that happened, with its closed payload.
        var submitted = AiRunEvent.submitted(run, 1);
        check(submitted.type() == AiRunEvent.Type.SUBMITTED && submitted.seq() == 1);
        check("PENDING".equals(submitted.payload().status()) && Integer.valueOf(0).equals(submitted.payload().attempt()));
        check(submitted.occurredAt().equals(run.createdAt()) && submitted.payloadVersion() == 1);
        var claimedRun = run.claimed(clock.now.plusSeconds(1));
        var claimed = AiRunEvent.claimed(claimedRun, 2);
        check(claimed.type() == AiRunEvent.Type.CLAIMED && claimed.payload().attempt() == 1);
        check("RUNNING".equals(claimed.payload().status()));
        var waiting = claimedRun.transitionTo(new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, clock.now.plusSeconds(2)));
        check(AiRunEvent.transitioned(waiting, 3).type() == AiRunEvent.Type.TOOL_WAIT);
        check("WAITING_TOOL".equals(AiRunEvent.transitioned(waiting, 3).payload().status()));
        var resumed = waiting.transitionTo(AiRun.Transition.to(AiRun.Status.RUNNING, clock.now.plusSeconds(3)));
        check(AiRunEvent.transitioned(resumed, 4).type() == AiRunEvent.Type.STATUS_CHANGED);
        check("RUNNING".equals(AiRunEvent.transitioned(resumed, 4).payload().status()));
        var disclosure = new AiRunControl.Reconciliation(false, false, 1, 1, true);
        var failed = resumed.transitionTo(new AiRun.Transition(AiRun.Status.FAILED, AiRun.Reason.RESULT_LOST, disclosure,
            clock.now.plusSeconds(4)));
        var finished = AiRunEvent.transitioned(failed, 5);
        check(finished.type() == AiRunEvent.Type.FINISHED);
        check("FAILED".equals(finished.payload().status()) && "RESULT_LOST".equals(finished.payload().reason()));
        check(finished.payload().disclosure() == disclosure && finished.payload().attempt() == null);
        var done = resumed.transitionTo(new AiRun.Transition(AiRun.Status.SUCCEEDED, null, null, clock.now.plusSeconds(4)));
        check(AiRunEvent.transitioned(done, 5).type() == AiRunEvent.Type.FINISHED);
        check("SUCCEEDED".equals(AiRunEvent.transitioned(done, 5).payload().status())
            && AiRunEvent.transitioned(done, 5).payload().reason() == null);
        var cancel = AiRunEvent.cancelRequested(uuid, 6, clock.now.plusSeconds(5));
        check(cancel.type() == AiRunEvent.Type.CANCEL_REQUESTED && cancel.payload().status() == null
            && cancel.payload().attempt() == null && cancel.payload().reason() == null && cancel.payload().disclosure() == null);
        var reconciled = AiRunEvent.reconciled(uuid, 7, disclosure, clock.now.plusSeconds(6));
        check(reconciled.type() == AiRunEvent.Type.RECONCILED && reconciled.payload().disclosure() == disclosure);
        check(reconciled.payload().status() == null && reconciled.payload().reason() == null);

        // Type and payload close each other: no mismatched, missing or extra payload field is a record.
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.SUBMITTED, clock.now, 1, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.SUBMITTED, clock.now, 1,
            AiRunEvent.Payload.claim(AiRun.Status.RUNNING, 0)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.CLAIMED, clock.now, 1,
            AiRunEvent.Payload.claim(AiRun.Status.RUNNING, 0)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.CLAIMED, clock.now, 1,
            AiRunEvent.Payload.claim(AiRun.Status.PENDING, 1)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.TOOL_WAIT, clock.now, 1,
            AiRunEvent.Payload.state(AiRun.Status.RUNNING)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.STATUS_CHANGED, clock.now, 1,
            AiRunEvent.Payload.state(AiRun.Status.SUCCEEDED)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1,
            AiRunEvent.Payload.state(AiRun.Status.PENDING)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.RECONCILED, clock.now, 1, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.FINISHED, clock.now, 1,
            AiRunEvent.Payload.state(AiRun.Status.RUNNING)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.FINISHED, clock.now, 1,
            AiRunEvent.Payload.landing(AiRun.Status.FAILED, null, null)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.FINISHED, clock.now, 1,
            AiRunEvent.Payload.landing(AiRun.Status.SUCCEEDED, AiRun.Reason.DEADLINE, null)));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.FINISHED, clock.now, 1,
            new AiRunEvent.Payload("SUCCEEDED", 1, null, null)));
        invalid(() -> new AiRunEvent.Event(uuid, 0, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 2, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Event(uuid, 1, AiRunEvent.Type.CANCEL_REQUESTED, null, 1, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Event(null, 1, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1, AiRunEvent.Payload.empty()));
        invalid(() -> new AiRunEvent.Payload("QUEUED", 0, null, null));
        invalid(() -> new AiRunEvent.Payload("PENDING", -1, null, null));
        invalid(() -> new AiRunEvent.Payload("PENDING", 0, "PROVIDER_DOWN", null));
        invalid(() -> AiRunEvent.Payload.reconciliation(null));

        // Sequence numbers are ordered and the cursor is a decimal position, never a guessed one.
        var late = new AiRunEvent.Event(uuid, 9, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1, AiRunEvent.Payload.empty());
        var early = new AiRunEvent.Event(uuid, 2, AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1, AiRunEvent.Payload.empty());
        check(AiRunEvent.order(List.of(late, early)).get(0).seq() == 2);
        check(AiRunEvent.cursor(null) == 0 && AiRunEvent.cursor("0") == 0 && AiRunEvent.cursor("42") == 42);
        for (var bad : List.of("", "-1", "01", "1.5", "x", " 1", "99999999999999999999")) {
            invalid(() -> AiRunEvent.cursor(bad));
        }

        // Gap semantics: an expired cursor answers with the retained window plus the current snapshot.
        var snapshot = AiRunEvent.Snapshot.of(run);
        check("PENDING".equals(snapshot.status()) && snapshot.statusVersion() == 0 && snapshot.attempt() == 0
            && !snapshot.terminal() && snapshot.updatedAt().equals(run.updatedAt()));
        var bounds = new AiRunEvent.Bounds(5, 9);
        var rows = List.of(early);
        var gapPage = AiRunEvent.Page.of(uuid, rows, 0, 20, bounds, snapshot);
        check(gapPage.gap() && !gapPage.hasMore() && gapPage.nextCursor() == null && gapPage.items().size() == 1);
        check(AiRunEvent.Page.of(uuid, rows, 3, 20, bounds, snapshot).gap());
        check(!AiRunEvent.Page.of(uuid, rows, 4, 20, bounds, snapshot).gap());
        check(!AiRunEvent.Page.of(uuid, rows, 9, 20, bounds, snapshot).gap());
        check(!AiRunEvent.Page.of(uuid, List.of(), 0, 20, AiRunEvent.Bounds.none(), snapshot).gap());
        var more = AiRunEvent.Page.of(uuid, List.of(early, late), 0, 1, bounds, snapshot);
        check(more.hasMore() && "2".equals(more.nextCursor()) && more.items().size() == 1);
        check(AiRunEvent.Page.of(uuid, List.of(early, late), 0, 2, bounds, snapshot).nextCursor() == null);
        invalid(() -> new AiRunEvent.Page(uuid, List.of(early), "3", false, false, snapshot));
        invalid(() -> new AiRunEvent.Page(uuid, List.of(early), null, true, false, snapshot));
        invalid(() -> new AiRunEvent.Page(uuid, List.of(early, early), null, false, false, snapshot));
        invalid(() -> new AiRunEvent.Page(uuid, List.of(late, early), null, false, false, snapshot));
        invalid(() -> new AiRunEvent.Page(uuid, List.of(new AiRunEvent.Event(UUID.randomUUID(), 1,
            AiRunEvent.Type.CANCEL_REQUESTED, clock.now, 1, AiRunEvent.Payload.empty())), null, false, false, snapshot));
        invalid(() -> AiRunEvent.Page.of(uuid, rows, -1, 20, bounds, snapshot));
        invalid(() -> AiRunEvent.Page.of(uuid, rows, 0, 0, bounds, snapshot));
        invalid(() -> AiRunEvent.Page.of(uuid, rows, 0, 51, bounds, snapshot));
        invalid(() -> new AiRunEvent.Bounds(3, 2));
        invalid(() -> new AiRunEvent.Snapshot("QUEUED", 0, 0, clock.now));

        // The store records events at the state transition points and reading never writes.
        check(store.read(tenant, uuid, 0, 50).size() == 1);
        check(store.read(tenant, uuid, 0, 50).get(0).type() == AiRunEvent.Type.SUBMITTED);
        var claim = store.claim(tenant, uuid, "worker", clock.now.plusSeconds(1), Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        check(store.read(tenant, uuid, 0, 50).size() == 2);
        check(store.read(tenant, uuid, 0, 50).get(1).type() == AiRunEvent.Type.CLAIMED);
        check(store.read(tenant, uuid, 0, 50).get(1).payload().attempt() == 1);
        store.advance(tenant, uuid, claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, clock.now.plusSeconds(2)));
        store.append(tenant, uuid, AiRunEvent.Type.RECONCILED, clock.now.plusSeconds(3),
            AiRunEvent.Payload.reconciliation(AiRunControl.Reconciliation.clean()));
        store.advance(tenant, uuid, 2, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, clock.now.plusSeconds(4)));
        store.requestCancel(tenant, uuid, clock.now.plusSeconds(5));
        store.requestCancel(tenant, uuid, clock.now.plusSeconds(6));
        var log = store.read(tenant, uuid, 0, 50);
        check(log.size() == 6);
        check(log.get(0).seq() == 1 && log.get(1).seq() == 2 && log.get(2).seq() == 3 && log.get(3).seq() == 4
            && log.get(4).seq() == 5 && log.get(5).seq() == 6);
        check(log.get(2).type() == AiRunEvent.Type.TOOL_WAIT && log.get(3).type() == AiRunEvent.Type.RECONCILED
            && log.get(4).type() == AiRunEvent.Type.STATUS_CHANGED && log.get(5).type() == AiRunEvent.Type.CANCEL_REQUESTED);
        // A repeated cancellation request is recorded once (the first request wins).
        check(store.bounds(tenant, uuid).lowestSeq() == 1 && store.bounds(tenant, uuid).highestSeq() == 6);
        check(store.read(tenant, uuid, 4, 50).size() == 2 && store.read(tenant, uuid, 4, 50).get(0).seq() == 5);
        check(store.read(tenant, uuid, 6, 50).isEmpty());
        // Replay is side-effect free: the same cursor answers with the same records and state stands still.
        var before = store.find(tenant, uuid).orElseThrow();
        check(store.read(tenant, uuid, 2, 50).equals(store.read(tenant, uuid, 2, 50)));
        check(store.find(tenant, uuid).orElseThrow().equals(before));
        check(store.bounds(tenant, uuid).equals(new AiRunEvent.Bounds(1, 6)));
        invalid(() -> store.append(tenant, UUID.randomUUID(), AiRunEvent.Type.RECONCILED, clock.now,
            AiRunEvent.Payload.reconciliation(AiRunControl.Reconciliation.clean())));
        invalid(() -> store.read(tenant, uuid, -1, 50));
        invalid(() -> store.read(tenant, uuid, 0, 0));

        // The retained window is what expires a cursor: dropping old rows marks the gap explicitly.
        var trimmed = new InMemoryAiRunStore(3);
        var service2 = new AiRunService(auth, incidentService, trimmed, clock);
        var second = service2.submit(p, new AiRun.Command(UUID.randomUUID(), incident, "Second question", window, "current", skill));
        for (int i = 0; i < 5; i++) {
            trimmed.append(tenant, second.runId(), AiRunEvent.Type.RECONCILED, clock.now.plusSeconds(i),
                AiRunEvent.Payload.reconciliation(AiRunControl.Reconciliation.clean()));
        }
        check(trimmed.bounds(tenant, second.runId()).equals(new AiRunEvent.Bounds(4, 6)));
        check(trimmed.read(tenant, second.runId(), 0, 50).size() == 3);
        var trimmedPage = AiRunEvent.Page.of(second.runId(), trimmed.read(tenant, second.runId(), 0, 50), 0, 20,
            trimmed.bounds(tenant, second.runId()), AiRunEvent.Snapshot.of(second));
        check(trimmedPage.gap() && trimmedPage.items().get(0).seq() == 4);
        check(!AiRunEvent.Page.of(second.runId(), trimmed.read(tenant, second.runId(), 3, 50), 3, 20,
            trimmed.bounds(tenant, second.runId()), AiRunEvent.Snapshot.of(second)).gap());
        check(!AiRunEvent.Page.of(second.runId(), List.of(), 7, 20,
            trimmed.bounds(tenant, second.runId()), AiRunEvent.Snapshot.of(second)).gap());

        System.out.println("AiRunEventSmoke: " + checks + " checks");
    }
}
