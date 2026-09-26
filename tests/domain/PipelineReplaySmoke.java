import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PipelineReplaySmoke {
    private static int checks;
    public static void main(String[] args) {
        var tenant = new TenantId("replay-tenant");
        var owner = new SubjectId("replay-owner");
        var principal = new Principal(owner, tenant, Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ), ResourceScope.tenantWide());
        var auth = new AuthorizeUseCase(); var inventory = new InMemoryInventoryStore();
        var versions = new InMemoryPipelineVersionStore(); var raw = new InMemoryRawRecordStore(); var syncs = new InMemorySyncRunStore();
        var sync = new IngestZabbixHostsUseCase(auth, new FixtureZabbixHostConnector(), inventory, raw, syncs, versions,
            "labeled-fixture", "memory", "zabbix-1", "unused", 1).execute(principal, null);
        var ref = versions.pinned(tenant, "zabbix-1", sync.syncRunId()).orElseThrow();
        var spec = new PipelineReplaySpec(sync.syncRunId(), ref, 100, "COMPARE_VERSION");
        var clock = new MutableClock(); var store = new InMemoryPipelineReplayStore(); var reads = new AtomicInteger();
        RawRecordReader reader = (t, s, r, l) -> { reads.incrementAndGet(); return raw.read(t, s, r, l); };
        var evaluator = new HostPipelineService(auth, versions, reader, syncs, "zabbix-1");
        var service = new PipelineReplayService(auth, store, evaluator, "zabbix-1", clock);
        var key = UUID.randomUUID(); var before = inventory.list(tenant);
        var run = service.execute(principal, key, spec);
        require(run.state() == PipelineReplayRun.State.SUCCEEDED && run.report().accepted() == 2, "replay succeeds and stores report");
        require(service.execute(principal, key, spec).equals(run) && reads.get() == 1, "same key returns saved result without reevaluation");
        require(service.get(principal, run.id()).report().equals(run.report()), "saved report is readable");
        require(inventory.list(tenant).equals(before), "durable replay does not write inventory");
        code(() -> service.execute(principal, key, new PipelineReplaySpec(sync.syncRunId(), ref, 1, "COMPARE_VERSION")), PipelineException.Code.REPLAY_KEY_CONFLICT, "same key cannot change request");
        var other = new Principal(new SubjectId("another-owner"), tenant, principal.permissions(), principal.resourceScope());
        code(() -> service.get(other, run.id()), PipelineException.Code.NOT_FOUND, "different owner cannot read");
        require(service.list(other, null, 20).items().isEmpty(), "different owner history is empty");
        require(store.find(new TenantId("other"), "zabbix-1", owner, run.id()).isEmpty(), "tenant isolation");
        require(store.find(tenant, "other-source", owner, run.id()).isEmpty(), "source isolation");
        var revoked = new Principal(owner, tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        code(() -> service.get(revoked, run.id()), PipelineException.Code.FORBIDDEN, "stored result rechecks entity permission");
        code(() -> service.execute(revoked, key, spec), PipelineException.Code.FORBIDDEN, "idempotent response cannot bypass current permission");
        var scoped = new Principal(owner, tenant, principal.permissions(), ResourceScope.of(Set.of(ResourceRef.source(tenant, "zabbix-1"))));
        code(() -> service.get(scoped, run.id()), PipelineException.Code.FORBIDDEN, "stored result rechecks entity scope");
        require(service.list(revoked, null, 20).items().size() == 1, "headers contain no entity report");
        var activeKey = UUID.randomUUID(); var first = store.claim(tenant, "zabbix-1", owner, activeKey, spec, clock.instant());
        require(first.acquired() && first.run().attempt() == 1, "first lease");
        require(!store.claim(tenant, "zabbix-1", owner, activeKey, spec, clock.instant()).acquired(), "active lease cannot be stolen");
        clock.advance(121);
        var second = store.claim(tenant, "zabbix-1", owner, activeKey, spec, clock.instant());
        require(second.acquired() && second.run().id().equals(first.run().id()) && second.run().attempt() == 2, "expired lease resumes same id");
        require(!store.finish(first.run(), clock.instant(), run.report(), null), "stale attempt cannot finish");
        require(store.finish(second.run(), clock.instant(), run.report(), null), "current attempt can finish");
        require(!store.finish(second.run(), clock.instant(), null, "LATE_ERROR"), "terminal result cannot be overwritten");
        var exhaustedKey = UUID.randomUUID();
        for (int i = 0; i < 3; i++) { require(store.claim(tenant, "zabbix-1", owner, exhaustedKey, spec, clock.instant()).acquired(), "bounded attempt " + i); clock.advance(121); }
        var exhausted = store.claim(tenant, "zabbix-1", owner, exhaustedKey, spec, clock.instant());
        require(!exhausted.acquired() && exhausted.run().state() == PipelineReplayRun.State.FAILED && !exhausted.run().canResume(clock.instant()), "attempt limit terminates expired work");
        var failCount = new AtomicInteger();
        var failing = new HostPipelineService(auth, versions, (t, s, r, l) -> {
            if (failCount.incrementAndGet() == 1) throw new IllegalStateException("sensitive source message");
            return raw.read(t, s, r, l);
        }, syncs, "zabbix-1");
        var failureService = new PipelineReplayService(auth, store, failing, "zabbix-1", clock);
        var failureKey = UUID.randomUUID();
        try { failureService.execute(principal, failureKey, spec); throw new AssertionError("failure must propagate"); }
        catch (IllegalStateException expected) { checks++; }
        var failed = service.list(principal, null, 50).items().stream().filter(item -> item.requestKey().equals(failureKey)).findFirst().orElseThrow();
        require(failed.state() == PipelineReplayRun.State.FAILED && failed.failureCode().equals("REPLAY_EVALUATION_FAILED"), "failure stores safe code");
        require(failCount.get() == 1, "no hidden retry");
        var retry = failureService.execute(principal, failureKey, spec);
        require(retry.id().equals(failed.id()) && retry.attempt() == 2 && retry.state() == PipelineReplayRun.State.SUCCEEDED, "explicit retry same identity");
        var page1 = service.list(principal, null, 2); var page2 = service.list(principal, page1.nextCursor(), 2);
        require(page1.nextCursor() != null && Collections.disjoint(page1.items().stream().map(PipelineReplayStore.Summary::id).toList(),
            page2.items().stream().map(PipelineReplayStore.Summary::id).toList()), "stable history cursor");
        code(() -> service.list(other, run.id(), 20), PipelineException.Code.NOT_FOUND, "foreign cursor is rejected");
        code(() -> service.list(principal, null, 51), PipelineException.Code.INVALID_REQUEST, "history budget enforced");
        require(service.get(principal, run.id()).report().equals(run.report()), "later runs never replace earlier success");
        System.out.println("Pipeline replay smoke: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void code(Runnable work, PipelineException.Code code, String message) {
        try { work.run(); throw new AssertionError(message); } catch (PipelineException error) { require(error.code() == code, message); }
    }
    private static final class MutableClock extends Clock {
        private Instant value = Instant.parse("2026-09-25T00:00:00Z");
        void advance(long seconds) { value = value.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
}
