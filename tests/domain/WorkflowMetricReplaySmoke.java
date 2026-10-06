import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricReplay.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Explicit synthetic protocol fixture; no real source or time-series claim. */
public final class WorkflowMetricReplaySmoke {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Replay check " + checks); }
    static void fail(WorkflowFailure.Code code, Runnable work) { checks++; try { work.run(); } catch (WorkflowFailure f) { if (f.code() == code) return; throw f; } throw new AssertionError("Expected " + code); }
    static final class Fixture {
        final WorkflowMetricOutputSmoke.Fixture f = new WorkflowMetricOutputSmoke.Fixture();
        final Principal p = new Principal(f.p.subjectId(), f.p.tenantId(), Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ, Permission.WORKFLOW_REPLAY), ResourceScope.tenantWide());
        final Semaphore budget = new Semaphore(2); WorkflowMetricReplayService service;
        int reads, count = 2; boolean changed, unavailable, revoke; Runnable duringRead = () -> {};
        final Instant from = f.clock.instant().minusSeconds(122).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Fixture() throws Exception { service = service(f.clock); }
        WorkflowMetricReplayService service(Clock clock) { return new WorkflowMetricReplayService(f.store, f.workflows, (principal, src, start, till) -> {
            reads++; duringRead.run(); if (unavailable) throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE); if (revoke) f.revokeAt = 1;
            return java.util.stream.IntStream.range(0, count).mapToObj(i -> Map.<String,Object>of("timestamp", start.plusMillis(i * 50L).toString(), "sourceKey", src.metric().sourceKey(), "value", changed ? "15" : "12.5")).toList();
        }, f.sink, budget, clock); }
        Command command() { return new Command(UUID.randomUUID(), f.d.id(), 1, f.d.digest(), from, from.plusSeconds(60)); }
        Execute execute(Plan plan) { return new Execute(UUID.randomUUID(), plan.requestId(), plan.proof().inputDigest(), plan.proof().batchDigest()); }
    }
    public static void main(String[] args) throws Exception {
        var a = new Fixture(); var c = a.command(); var plan = a.service.create(a.p, c);
        check(plan.state().equals("READY") && plan.proof().inputCount() == 2 && plan.proof().timestamps().size() == 2);
        check(a.reads == 1 && a.f.sink.writes == 0); check(!plan.notifications() && !plan.actions());
        check(plan.proof().labels().get("collection_mode").equals("REPLAY_60S") && plan.requestId().toString().equals(plan.proof().labels().get("replay_id")));
        check(a.service.create(a.p, c).equals(plan) && a.reads == 1); check(a.service.plans(a.p, plan.reference()).items().equals(List.of(plan)));
        var execute = a.execute(plan); var result = a.service.execute(a.p, execute); check(result.state().equals("CONFIRMED") && a.reads == 2 && a.f.sink.writes == 1);
        check(a.f.sink.batch.samples().getFirst().value().toPlainString().equals("0.125"));
        check(a.service.execute(a.p, execute).equals(result) && a.reads == 2 && a.f.sink.writes == 1);
        check(a.service.verify(a.p, execute.requestId()).equals(result) && a.f.sink.reads == 0);
        fail(WorkflowFailure.Code.CONFLICT, () -> a.service.execute(a.p, a.execute(plan)));
        fail(WorkflowFailure.Code.CONFLICT, () -> a.service.create(a.p, new Command(c.requestId(), c.id(), c.revision(), c.digest(), c.from().minusSeconds(60), c.from())));
        fail(WorkflowFailure.Code.FORBIDDEN, () -> a.service.create(a.f.p, a.command()));
        var other = new Principal(new SubjectId("other-replay"), a.p.tenantId(), a.p.permissions(), ResourceScope.tenantWide());
        fail(WorkflowFailure.Code.NOT_FOUND, () -> a.service.plan(other, plan.requestId()));
        var changed = new Fixture(); var cp = changed.service.create(changed.p, changed.command()); changed.changed = true;
        var rejected = changed.service.execute(changed.p, changed.execute(cp)); check(rejected.state().equals("FAILED") && rejected.error().equals("SOURCE_WINDOW_CHANGED") && changed.f.sink.writes == 0);
        var unknown = new Fixture(); var up = unknown.service.create(unknown.p, unknown.command()); var uc = unknown.execute(up); unknown.f.sink.unknown = true;
        var ur = unknown.service.execute(unknown.p, uc); check(ur.state().equals("UNKNOWN") && unknown.f.sink.writes == 1);
        check(unknown.service.execute(unknown.p, uc).equals(ur) && unknown.reads == 2); check(unknown.service.verify(unknown.p, uc.requestId()).state().equals("UNKNOWN"));
        check(unknown.f.sink.writes == 1 && unknown.reads == 2); unknown.f.sink.visible = unknown.f.sink.batch.samples();
        check(unknown.service.verify(unknown.p, uc.requestId()).state().equals("CONFIRMED") && unknown.f.sink.writes == 1 && unknown.reads == 2);
        var empty = new Fixture(); empty.count = 0; var ep = empty.service.create(empty.p, empty.command()); check(ep.proof().inputCount() == 0);
        check(empty.service.execute(empty.p, empty.execute(ep)).state().equals("CONFIRMED") && empty.f.sink.writes == 0);
        var overflow = new Fixture(); overflow.count = 61; var op = overflow.service.create(overflow.p, overflow.command());
        check(op.state().equals("FAILED") && op.error().equals("INVALID_SAMPLE") && overflow.f.sink.writes == 0);
        var full = new Fixture(); full.count = 60; var fp = full.service.create(full.p, full.command()); check(fp.proof().timestamps().size() == 60);
        check(full.service.execute(full.p, full.execute(fp)).state().equals("CONFIRMED") && full.f.sink.batch.samples().size() == 60);
        var expired = new Fixture(); var xp = expired.service.create(expired.p, expired.command());
        fail(WorkflowFailure.Code.CONFLICT, () -> expired.service(Clock.offset(expired.f.clock, Duration.ofSeconds(600))).execute(expired.p, expired.execute(xp)));
        check(expired.reads == 1 && expired.f.sink.writes == 0); check(expired.service(Clock.offset(expired.f.clock, Duration.ofDays(2))).plan(expired.p, xp.requestId()).equals(xp));
        var unavailable = new Fixture(); unavailable.unavailable = true; var nc = unavailable.command(); var np = unavailable.service.create(unavailable.p, nc);
        check(np.state().equals("FAILED") && np.error().equals("SOURCE_UNAVAILABLE")); unavailable.unavailable = false;
        check(unavailable.service.create(unavailable.p, nc).equals(np) && unavailable.reads == 1);
        var revoked = new Fixture(); var rp = revoked.service.create(revoked.p, revoked.command()); revoked.revoke = true;
        var rr = revoked.service.execute(revoked.p, revoked.execute(rp)); check(rr.error().equals("FORBIDDEN") && revoked.f.sink.writes == 0);
        var busy = new Fixture(); busy.budget.acquire(2); fail(WorkflowFailure.Code.BUSY, () -> busy.service.create(busy.p, busy.command())); check(busy.reads == 0); busy.budget.release(2);
        var tooRecent = new Command(UUID.randomUUID(), c.id(), 1, c.digest(), a.f.clock.instant().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS), a.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        fail(WorkflowFailure.Code.CONFLICT, () -> a.service.create(a.p, tooRecent)); check(a.reads == 2);
        check(!plan.toString().contains("12.5") && !result.toString().contains("0.125"));
        check(a.f.store.transaction(a.p.tenantId(), s -> s.metricStreamTask(a.p.subjectId().value(), a.f.d.id()).isEmpty()));
        var early = new Fixture(); var earlyPlan = early.service.create(early.p, early.command()); var earlyCommand = early.execute(earlyPlan);
        early.duringRead = () -> { var pending = early.service.verify(early.p, earlyCommand.requestId()); check(pending.state().equals("PENDING") && early.f.sink.reads == 0); };
        check(early.service.execute(early.p, earlyCommand).state().equals("CONFIRMED") && early.f.sink.writes == 1);
        System.out.println("Workflow metric replay smoke: " + checks + " checks passed");
    }
}
