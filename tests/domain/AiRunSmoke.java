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
 * Durable run registry semantics (ADR-104 5a): the closed state machine, idempotent submission with
 * explicit conflicts, read-only readback and bounded cursor pages. No store I/O and no model call is
 * involved here; the real table shapes and the compare-and-set are covered by the PostgreSQL IT.
 */
public class AiRunSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("AI run check " + checks); }
    static void invalid(Runnable action) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    static void fails(ToolFailure.Code code, Runnable action) {
        checks++;
        try { action.run(); }
        catch (ToolFailure failure) { if (failure.code() == code) return; throw new AssertionError(failure.code() + " != " + code); }
        catch (IncidentFailure failure) { if (code.name().equals(failure.code().name())) return; throw new AssertionError(failure.code() + " != " + code); }
        throw new AssertionError("Expected " + code + " " + checks);
    }
    static void conflict(String code, Runnable action) {
        checks++;
        try { action.run(); } catch (AiRun.Conflict failure) { if (failure.code().equals(code)) return; throw new AssertionError(failure.code() + " != " + code); }
        throw new AssertionError("Expected " + code + " " + checks);
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    public static void main(String[] args) {
        // The state machine is closed: only the documented transitions exist and terminals are final.
        for (var status : AiRun.Status.values()) {
            check(status.terminal() == (status == AiRun.Status.SUCCEEDED || status == AiRun.Status.FAILED || status == AiRun.Status.CANCELLED));
        }
        check(AiRun.Status.PENDING.canTransitionTo(AiRun.Status.RUNNING));
        check(AiRun.Status.PENDING.canTransitionTo(AiRun.Status.FAILED));
        check(AiRun.Status.PENDING.canTransitionTo(AiRun.Status.CANCELLED));
        check(!AiRun.Status.PENDING.canTransitionTo(AiRun.Status.SUCCEEDED));
        check(!AiRun.Status.PENDING.canTransitionTo(AiRun.Status.WAITING_TOOL));
        check(!AiRun.Status.PENDING.canTransitionTo(AiRun.Status.PENDING));
        check(AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.WAITING_TOOL));
        check(AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.SUCCEEDED));
        check(AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.FAILED));
        check(AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.CANCELLED));
        check(!AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.PENDING));
        check(!AiRun.Status.RUNNING.canTransitionTo(AiRun.Status.RUNNING));
        check(AiRun.Status.WAITING_TOOL.canTransitionTo(AiRun.Status.RUNNING));
        check(AiRun.Status.WAITING_TOOL.canTransitionTo(AiRun.Status.FAILED));
        check(AiRun.Status.WAITING_TOOL.canTransitionTo(AiRun.Status.CANCELLED));
        check(!AiRun.Status.WAITING_TOOL.canTransitionTo(AiRun.Status.SUCCEEDED));
        check(!AiRun.Status.WAITING_TOOL.canTransitionTo(AiRun.Status.WAITING_TOOL));
        for (var terminal : List.of(AiRun.Status.SUCCEEDED, AiRun.Status.FAILED, AiRun.Status.CANCELLED)) {
            for (var next : AiRun.Status.values()) check(!terminal.canTransitionTo(next));
        }
        invalid(() -> AiRun.Status.PENDING.canTransitionTo(null));

        var clock = new TestClock();
        var tenant = new TenantId("ai-run-domain");
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
        var store = new InMemoryAiRunStore();
        var service = new AiRunService(auth, incidentService, store, clock);
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(clock.now.minusSeconds(600), clock.now.minusSeconds(300));
        var command = new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);

        // The submission digest covers content only; the request id is correlation metadata.
        check(command.commandDigest().matches("sha256:[0-9a-f]{64}"));
        check(command.commandDigest().equals(new AiRun.Command(command.requestId(), incident, "Summarize the incident",
            window, "current", skill).commandDigest()));
        check(command.commandDigest().equals(new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident",
            window, "current", skill).commandDigest()));
        check(!command.commandDigest().equals(new AiRun.Command(command.requestId(), incident, "A different question",
            window, "current", skill).commandDigest()));
        for (var bad : List.<Runnable>of(
            () -> new AiRun.Command(null, incident, "q", window, "current", skill),
            () -> new AiRun.Command(command.requestId(), null, "q", window, "current", skill),
            () -> new AiRun.Command(command.requestId(), incident, "", window, "current", skill),
            () -> new AiRun.Command(command.requestId(), incident, "   ", window, "current", skill),
            () -> new AiRun.Command(command.requestId(), incident, "x".repeat(2001), window, "current", skill),
            () -> new AiRun.Command(command.requestId(), incident, "q", window, "historical", skill),
            () -> new AiRun.Command(command.requestId(), incident, "q", window, "current", null),
            () -> new AiRun.Command(command.requestId(), incident, "q", null, "current", skill))) invalid(bad);
        invalid(() -> new ToolWindow(clock.now, clock.now));
        invalid(() -> new ToolWindow(clock.now.minusSeconds(3601), clock.now));
        invalid(() -> new ToolWindow(clock.now, clock.now.minusSeconds(600)));

        // The record keeps its submission parts fixed and refuses malformed state.
        var run = new AiRun.Run(UUID.randomUUID(), tenant, user, command.requestId(), command.commandDigest(), incident,
            command.question(), command.window(), command.knowledgeMode(), command.skill(), AiRun.Status.PENDING, 0,
            clock.now, clock.now);
        invalid(() -> new AiRun.Run(run.runId(), tenant, user, command.requestId(), "sha256:zz", incident, "q", window,
            "current", skill, AiRun.Status.PENDING, 0, clock.now, clock.now));
        invalid(() -> new AiRun.Run(run.runId(), tenant, user, command.requestId(), command.commandDigest(), incident, "q",
            window, "current", skill, AiRun.Status.PENDING, -1, clock.now, clock.now));
        invalid(() -> new AiRun.Run(run.runId(), tenant, user, command.requestId(), command.commandDigest(), incident, "q",
            window, "current", skill, AiRun.Status.PENDING, 0, clock.now.plusSeconds(1), clock.now));
        run.require(command);
        conflict("AI_RUN_REQUEST_REUSED", () -> run.require(new AiRun.Command(command.requestId(), incident,
            "A different question", window, "current", skill)));
        conflict("AI_RUN_REQUEST_REUSED", () -> run.require(new AiRun.Command(command.requestId(), incident,
            command.question(), window, "current", new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "d".repeat(64)))));

        // Transitions carry the version forward; terminals never move again and time never goes back.
        var running = run.transitionTo(AiRun.Status.RUNNING, clock.now.plusSeconds(1));
        check(running.status() == AiRun.Status.RUNNING && running.statusVersion() == 1);
        check(running.createdAt().equals(run.createdAt()) && running.updatedAt().equals(clock.now.plusSeconds(1)));
        conflict("AI_RUN_STATE_CONFLICT", () -> running.transitionTo(AiRun.Status.PENDING, clock.now.plusSeconds(2)));
        conflict("AI_RUN_STATE_CONFLICT", () -> running.transitionTo(AiRun.Status.SUCCEEDED, clock.now));
        var done = running.transitionTo(AiRun.Status.SUCCEEDED, clock.now.plusSeconds(2));
        conflict("AI_RUN_STATE_CONFLICT", () -> done.transitionTo(AiRun.Status.RUNNING, clock.now.plusSeconds(3)));

        // Idempotent submission: one request id, one run, one answer.
        var submitted = service.submit(p, command);
        check(submitted.status() == AiRun.Status.PENDING && submitted.statusVersion() == 0);
        check(service.submit(p, command).equals(submitted));
        check(service.get(p, submitted.runId()).equals(submitted));
        conflict("AI_RUN_REQUEST_REUSED", () -> service.submit(p, new AiRun.Command(command.requestId(), incident,
            "A different question", window, "current", skill)));
        var other = new Principal(new SubjectId("other"), tenant, p.permissions(), p.resourceScope());
        conflict("AI_RUN_REQUEST_REUSED", () -> service.submit(other, command));
        var denied = new Principal(user, tenant, Set.of(Permission.AI_INSIGHT_READ), ResourceScope.tenantWide());
        fails(ToolFailure.Code.FORBIDDEN, () -> service.submit(denied, command));
        fails(ToolFailure.Code.FORBIDDEN, () -> service.get(denied, submitted.runId()));
        var cross = new Principal(user, new TenantId("other"), p.permissions(), ResourceScope.tenantWide());
        fails(ToolFailure.Code.NOT_FOUND, () -> service.get(cross, submitted.runId()));
        fails(ToolFailure.Code.NOT_FOUND, () -> service.get(p, UUID.randomUUID()));
        fails(ToolFailure.Code.INVALID_REQUEST, () -> service.submit(p, new AiRun.Command(UUID.randomUUID(), incident,
            "Future window", new ToolWindow(clock.now.plusSeconds(60), clock.now.plusSeconds(120)), "current", skill)));

        // Bounded cursor pages over one retained ordering; unknown cursors fail closed.
        clock.now = clock.now.plusSeconds(60);
        var second = service.submit(p, new AiRun.Command(UUID.randomUUID(), incident, "Second question",
            new ToolWindow(clock.now.minusSeconds(600), clock.now.minusSeconds(300)), "current", skill));
        clock.now = clock.now.plusSeconds(60);
        var third = service.submit(p, new AiRun.Command(UUID.randomUUID(), incident, "Third question",
            new ToolWindow(clock.now.minusSeconds(600), clock.now.minusSeconds(300)), "current", skill));
        var firstPage = service.list(p, null, 2);
        check(firstPage.items().size() == 2 && firstPage.hasMore());
        check(firstPage.items().get(0).runId().equals(third.runId()) && firstPage.items().get(1).runId().equals(second.runId()));
        check(firstPage.nextCursor().equals(second.runId().toString()));
        var secondPage = service.list(p, firstPage.nextCursor(), 2);
        check(secondPage.items().size() == 1 && !secondPage.hasMore() && secondPage.nextCursor() == null);
        check(secondPage.items().get(0).runId().equals(submitted.runId()));
        check(service.list(p, null, 50).items().size() == 3);
        fails(ToolFailure.Code.INVALID_REQUEST, () -> service.list(p, null, 0));
        fails(ToolFailure.Code.INVALID_REQUEST, () -> service.list(p, null, 51));
        fails(ToolFailure.Code.INVALID_REQUEST, () -> service.list(p, "NOT-A-CURSOR", 20));
        fails(ToolFailure.Code.NOT_FOUND, () -> service.list(p, UUID.randomUUID().toString(), 20));
        check(service.list(other, null, 20).items().isEmpty());
        // The retained ordering is createdAt DESC, runId DESC, ties included.
        var tied = new ArrayList<AiRun.Run>();
        var at = clock.now;
        for (int i = 0; i < 3; i++) {
            tied.add(new AiRun.Run(UUID.randomUUID(), tenant, user, UUID.randomUUID(), command.commandDigest(), incident,
                "tie " + i, window, "current", skill, AiRun.Status.PENDING, 0, at, at));
        }
        var ordered = AiRun.order(tied);
        check(ordered.get(0).runId().toString().compareTo(ordered.get(1).runId().toString()) > 0);
        check(AiRun.order(new ArrayList<>(List.of(ordered.get(1), ordered.get(2), ordered.get(0)))).equals(ordered));
        check(AiRun.strictlyAfter(ordered, null).equals(ordered));
        check(AiRun.strictlyAfter(ordered, ordered.get(0).runId().toString()).size() == 2);
        fails(ToolFailure.Code.NOT_FOUND, () -> AiRun.strictlyAfter(ordered, UUID.randomUUID().toString()));

        // The executor seam is a compare-and-set: stale writers never overwrite a newer state.
        check(store.advance(tenant, submitted.runId(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, clock.now)).orElseThrow().statusVersion() == 1);
        check(store.advance(tenant, submitted.runId(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, clock.now)).isEmpty());
        check(store.advance(tenant, submitted.runId(), 1, 0, AiRun.Transition.to(AiRun.Status.SUCCEEDED, clock.now.plusSeconds(1))).isPresent());
        conflict("AI_RUN_STATE_CONFLICT", () -> store.advance(tenant, submitted.runId(), 2, 0, AiRun.Transition.to(AiRun.Status.RUNNING, clock.now.plusSeconds(2))));
        check(store.advance(tenant, submitted.runId(), 3, 0, AiRun.Transition.to(AiRun.Status.RUNNING, clock.now.plusSeconds(2))).isEmpty());
        check(store.advance(tenant, UUID.randomUUID(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, clock.now)).isEmpty());
        check(store.byRequest(tenant, command.requestId()).orElseThrow().runId().equals(submitted.runId()));
        check(store.byRequest(tenant, UUID.randomUUID()).isEmpty());

        var page = AiRun.Page.of(AiRun.order(List.of(submitted, second, third)), 2);
        check(page.items().size() == 2 && page.hasMore() && page.nextCursor() != null);
        check(!AiRun.Page.of(List.of(submitted), 2).hasMore());
        check(AiRun.Page.of(List.of(), 2).items().isEmpty() && AiRun.Page.of(List.of(), 2).nextCursor() == null);
        invalid(() -> new AiRun.Page(List.of(), null, true));
        invalid(() -> new AiRun.Page(List.of(), "10000000-0000-4000-8000-000000000083", false));

        System.out.println("AiRunSmoke: " + checks + " checks passed");
    }
}
