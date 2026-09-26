import com.acme.opsweave.aicontrol.application.ToolGateway;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.InMemoryToolReadStore;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.domain.*;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.*;
import com.acme.opsweave.telemetry.infrastructure.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

public class ToolGatewaySmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("tools-test");
    static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    static final EntityId ENTITY = new EntityId(UUID.randomUUID());
    static final String METRIC = "host.cpu.usage.user";
    static final ToolWindow WINDOW = new ToolWindow(NOW.minusSeconds(3600), NOW);
    static class MutableClock extends Clock {
        Instant time = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time; }
    }
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static void fails(ToolFailure.Code code, Runnable work) {
        try { work.run(); throw new AssertionError("Expected " + code); } catch (ToolFailure failed) { check(failed.code() == code); }
    }
    static Principal principal(String user, Set<Permission> permissions, ResourceScope scope) { return new Principal(new SubjectId(user), TENANT, permissions, scope); }
    public static void main(String[] args) throws Exception {
        var clock = new MutableClock(); var auth = new AuthorizeUseCase();
        var problems = new InMemoryIncidentStore((tenant, source, hosts) -> Map.of("10084", ENTITY));
        var problem = new ExternalProblem(TENANT, "zabbix-1", "30001", "40001", "Fixture CPU", 3, NOW.minusSeconds(1200), NOW,
            List.of("10084"), false, null, null);
        var id = IncidentRecord.initialId(problem); problems.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(problem), NOW);
        var incidents = new IncidentService(auth, problems, clock); var store = new InMemoryToolReadStore(); var catalog = new InMemoryMetricDefinitionStore();
        catalog.upsert(new MetricDefinition(TENANT, METRIC, "CPU", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 3));
        var port = new InMemoryMetricQuery(List.of(
            new MetricSeries("zabbix-1", "labeled-fixture", "20001", 2, "1", Map.of("mode", "user"), List.of(new Sample(NOW.minusSeconds(10).toEpochMilli(), new BigDecimal("0.2")), new Sample(NOW.minusSeconds(5).toEpochMilli(), new BigDecimal("0.4")))),
            new MetricSeries("zabbix-2", "zabbix-jsonrpc", "20002", 1, "1", Map.of("mode", "user"), List.of(new Sample(NOW.minusSeconds(7).toEpochMilli(), new BigDecimal("0.8"))))));
        var query = new QueryMetricSeriesUseCase(auth, (tenant, entity) -> tenant.equals(TENANT) && entity.equals(ENTITY), catalog, port, clock);
        var gateway = new ToolGateway(auth, incidents, query, catalog, store, e -> 2000, clock);
        var permissions = Set.of(Permission.AI_DIAGNOSE, Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ);
        var p = principal("reader", permissions, ResourceScope.tenantWide());
        var session = gateway.create(p, id, WINDOW); check(session.incidentVersion() == 1); check(session.entityIds().equals(Set.of(ENTITY)));
        var incident = gateway.incident(p, session.id(), id); check(incident.dataModes().equals(List.of("labeled-fixture"))); check(incident.warnings().contains("LOGS_NOT_CONNECTED"));
        check(incident.incidentVersion() == 1); check(incident.availableAt().equals(NOW)); check(incident.expiresAt().equals(NOW.plusSeconds(86400)));
        var metric = gateway.metric(p, session.id(), id, METRIC, WINDOW, 500);
        check(metric.dataModes().equals(List.of("labeled-fixture", "zabbix-jsonrpc"))); check(metric.data().get("sampleCount").equals(3L)); check(metric.data().get("definitionVersion").equals(3L));
        check(metric.warnings().contains("METRIC_INGEST_TIME_UNAVAILABLE")); check(metric.queryWindow().equals(WINDOW));
        var entity = (Map<?,?>) ((List<?>) metric.data().get("entities")).getFirst(); var series = (List<?>) entity.get("series");
        check(series.size() == 2); check(((Map<?,?>)series.getFirst()).get("mean").equals("0.3")); check(((Map<?,?>)series.getLast()).get("mean").equals("0.8"));
        check(gateway.recheck(p, session.id(), incident.id()).equals(incident)); check(gateway.recheck(p, session.id(), metric.id()).equals(metric));
        fails(BUDGET_EXHAUSTED, () -> gateway.incident(p, session.id(), id));
        var other = principal("other", permissions, ResourceScope.tenantWide());
        fails(NOT_FOUND, () -> gateway.incident(other, session.id(), id));
        check(gateway.getEvidence(other, metric.id()).equals(metric)); // Owner binds the call budget, not otherwise authorized evidence readership.
        var denied = principal("reader", Set.of(Permission.AI_DIAGNOSE, Permission.INCIDENT_READ, Permission.ENTITY_READ), ResourceScope.tenantWide());
        fails(FORBIDDEN, () -> gateway.create(denied, id, WINDOW)); fails(FORBIDDEN, () -> gateway.getEvidence(denied, incident.id()));
        var scoped = principal("reader", permissions, ResourceScope.of(Set.of(ResourceRef.incident(TENANT, id), new ResourceRef(TENANT, "evidence", "*"), ResourceRef.metric(TENANT, METRIC))));
        fails(NOT_FOUND, () -> gateway.getEvidence(scoped, metric.id()));
        var second = gateway.create(p, id, WINDOW);
        fails(FORBIDDEN, () -> gateway.incident(p, second.id(), UUID.randomUUID()));
        fails(INVALID_REQUEST, () -> gateway.metric(p, second.id(), id, METRIC, new ToolWindow(NOW.minusSeconds(1800), NOW), 500));
        var missing = gateway.metric(p, second.id(), id, "missing", WINDOW, 500); check(missing.warnings().contains("METRIC_NOT_FOUND")); check(missing.dataModes().equals(List.of("unknown")));
        var unavailable = new ToolGateway(auth, incidents, new QueryMetricSeriesUseCase(auth, (t,e) -> true, catalog, new ClosedMetricQuery(), clock), catalog, store, e -> 2000, clock);
        fails(UNAVAILABLE, () -> unavailable.metric(p, second.id(), id, METRIC, WINDOW, 500));
        check(store.outcomes().contains("UNAVAILABLE")); check(store.outcomes().contains("FORBIDDEN"));
        var third = gateway.create(p, id, WINDOW);
        var oversized = new ToolGateway(auth, incidents, query, catalog, store, e -> 30001, clock);
        fails(READ_LIMIT, () -> oversized.incident(p, third.id(), id));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var changingCatalog = new com.acme.opsweave.telemetry.api.MetricDefinitionStore() {
            public Optional<MetricDefinition> find(TenantId tenant, String key) {
                var definition = catalog.find(tenant, key).orElseThrow();
                return Optional.of(new MetricDefinition(tenant, key, definition.displayName(), "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), calls.incrementAndGet()));
            }
            public void upsert(MetricDefinition definition) { throw new UnsupportedOperationException(); }
            public void upsert(MetricBinding binding) { throw new UnsupportedOperationException(); }
            public int retireMissing(TenantId tenant, String source, Set<String> ids) { throw new UnsupportedOperationException(); }
            public Optional<MetricBinding> findBinding(TenantId tenant, String source, String item) { return catalog.findBinding(tenant, source, item); }
            public List<MetricDefinition> list(TenantId tenant) { return catalog.list(tenant); }
            public List<MetricBinding> listBindings(TenantId tenant) { return catalog.listBindings(tenant); }
        };
        var changing = new ToolGateway(auth, incidents, query, changingCatalog, store, e -> 2000, clock);
        fails(INPUT_CHANGED, () -> changing.metric(p, third.id(), id, METRIC, WINDOW, 500));
        var fourth = gateway.create(p, id, WINDOW);
        fails(BUSY, () -> gateway.create(p, id, WINDOW));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var tasks = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 12; n++) tasks.add(executor.submit(() -> { gate.await(); try { store.begin(TENANT, p.subjectId(), fourth.id(), "incident.get@2.0.0", NOW); return true; }
                catch (ToolFailure failure) { if (failure.code() != BUDGET_EXHAUSTED) throw failure; return false; } }));
            gate.countDown(); int accepted = 0; for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) accepted++; check(accepted == 4);
        }
        var otherSession = gateway.create(other, id, WINDOW);
        var beforeChange = gateway.incident(other, otherSession.id(), id);
        var changeOnce = new java.util.concurrent.atomic.AtomicBoolean();
        var racing = new ToolGateway(auth, incidents, query, catalog, store, e -> {
            if (changeOnce.compareAndSet(false, true)) problems.transition(TENANT, id, new IncidentVisibility(true, Set.of(), true, Set.of()), 1, IncidentStatus.INVESTIGATING, UUID.randomUUID(), "reader", NOW);
            return 2000;
        }, clock);
        fails(INPUT_CHANGED, () -> racing.recheck(other, otherSession.id(), beforeChange.id()));
        fails(INPUT_CHANGED, () -> gateway.incident(p, third.id(), id));
        clock.time = NOW.plusSeconds(61); fails(EXPIRED, () -> gateway.incident(p, third.id(), id));
        check(gateway.create(p, id, WINDOW).incidentVersion() == 2);
        var beforeMergeSession = gateway.create(other, id, WINDOW);
        var beforeMerge = gateway.incident(other, beforeMergeSession.id(), id);
        var related = new ExternalProblem(TENANT, "zabbix-1", "30002", "40002", "Fixture shared entity", 3, NOW.minusSeconds(1200), NOW,
            List.of("10084"), false, null, null);
        var relatedId = IncidentRecord.initialId(related); problems.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(related), clock.instant());
        problems.reorganize(TENANT, new IncidentReorganization.Request(UUID.randomUUID(), IncidentReorganization.Kind.MERGE, relatedId, 1, id, 2, List.of(), null, "Fixture human merge"),
            new IncidentVisibility(true,Set.of(),true,Set.of()), "operator", clock.instant());
        fails(INPUT_CHANGED, () -> gateway.getEvidence(other, beforeMerge.id()));
        fails(INPUT_CHANGED, () -> gateway.recheck(other, beforeMergeSession.id(), beforeMerge.id()));
        fails(INPUT_CHANGED, () -> gateway.create(other, relatedId, WINDOW));
        var afterMergeSession = gateway.create(other, id, WINDOW); var afterMerge = gateway.incident(other, afterMergeSession.id(), id);
        check(afterMerge.entityIds().equals(beforeMerge.entityIds())); // Same entity still requires association-version invalidation.
        problems.reorganize(TENANT,new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.SPLIT,id,3,UUID.randomUUID(),0,
            List.of(new IncidentReorganization.ProblemKey("zabbix-1","30002")),"Fixture split","Human scope correction"),new IncidentVisibility(true,Set.of(),true,Set.of()),"operator",clock.instant());
        fails(INPUT_CHANGED, () -> gateway.getEvidence(other, afterMerge.id()));
        var currentSession = gateway.create(other, id, WINDOW); var currentSnapshot = gateway.incident(other, currentSession.id(), id);
        clock.time = NOW.plusSeconds(86462); fails(EXPIRED, () -> gateway.getEvidence(other, currentSnapshot.id()));
        fails(INVALID_REQUEST, () -> new ToolWindow(NOW.minusSeconds(3601), NOW));
        fails(INVALID_REQUEST, () -> new ToolWindow(NOW.minusSeconds(60).plusNanos(1), NOW));
        System.out.println("Tool gateway smoke: " + checks + " checks passed");
    }
}
