import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.MetricSeries;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.Sample;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.infrastructure.ClosedMetricQuery;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class QueryMetricSeriesSmoke {
    public static void main(String[] args) {
        var tenant = new TenantId("tenant-demo");
        var entity = new EntityId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        var store = new InMemoryMetricDefinitionStore();
        store.upsert(new MetricDefinition(tenant, "host.cpu.usage.user", "CPU User", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 1));
        var calls = new AtomicInteger();
        var port = new InMemoryMetricQuery(List.of(new MetricSeries("zabbix-1", "labeled-fixture", "20001", 1, "1", Map.of("mode", "user"),
            List.of(new Sample(1_600_000L, new BigDecimal("0.31"))))));
        var useCase = new QueryMetricSeriesUseCase(new AuthorizeUseCase(), (tenantId, entityId) -> entity.equals(entityId), store,
            query -> { calls.incrementAndGet(); return port.query(query); }, Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC));
        var reader = principal(Set.of(Permission.ENTITY_READ, Permission.METRIC_READ));
        var page = useCase.query(reader, entity, "host.cpu.usage.user", 1_000, 1_600, 100);
        if (page.kind() != QueryMetricSeriesUseCase.Result.Kind.AVAILABLE || !"1".equals(page.unit()) || page.page().series().size() != 1 || calls.get() != 1) {
            throw new AssertionError("Authorized query returns the catalog unit and stored series");
        }
        var denied = useCase.query(principal(Set.of(Permission.ENTITY_READ)), entity, "host.cpu.usage.user", 1_000, 1_600, 100);
        if (denied.kind() != QueryMetricSeriesUseCase.Result.Kind.FORBIDDEN || calls.get() != 1) {
            throw new AssertionError("metric.read is required and source.sync is not a substitute");
        }
        var missingEntity = new QueryMetricSeriesUseCase(new AuthorizeUseCase(), (tenantId, entityId) -> false, store, port,
            Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC)).query(reader, entity, "host.cpu.usage.user", 1_000, 1_600, 100);
        if (missingEntity.kind() != QueryMetricSeriesUseCase.Result.Kind.NOT_FOUND) throw new AssertionError("Missing entity");
        var missingMetric = useCase.query(reader, entity, "missing.metric", 1_000, 1_600, 100);
        if (missingMetric.kind() != QueryMetricSeriesUseCase.Result.Kind.NOT_FOUND || calls.get() != 1) throw new AssertionError("Missing metric does not query storage");
        var closed = new QueryMetricSeriesUseCase(new AuthorizeUseCase(), (tenantId, entityId) -> true, store, new ClosedMetricQuery(),
            Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC)).query(reader, entity, "host.cpu.usage.user", 1_000, 1_600, 100);
        if (closed.kind() != QueryMetricSeriesUseCase.Result.Kind.UNAVAILABLE || closed.page() != null) {
            throw new AssertionError("Closed storage is unavailable, not an empty series");
        }
        var future = useCase.query(reader, entity, "host.cpu.usage.user", 1_000, 9_000, 100);
        if (future.kind() != QueryMetricSeriesUseCase.Result.Kind.INVALID) throw new AssertionError("Future window");
        for (var scope : List.of(ResourceScope.of(Set.of(ResourceRef.entity(tenant, entity))),
            ResourceScope.of(Set.of(ResourceRef.metric(tenant, "host.cpu.usage.user"))))) {
            var scoped = new Principal(new SubjectId("user-demo"), tenant, reader.permissions(), scope);
            if (useCase.query(scoped, entity, "host.cpu.usage.user", 1_000, 1_600, 100).kind()
                != QueryMetricSeriesUseCase.Result.Kind.FORBIDDEN || calls.get() != 1) {
                throw new AssertionError("Both entity and metric object scopes are required before storage access");
            }
        }
        var other = new Principal(new SubjectId("other-user"), new TenantId("other-tenant"), reader.permissions(), ResourceScope.tenantWide());
        if (useCase.query(other, entity, "host.cpu.usage.user", 1_000, 1_600, 100).kind()
            != QueryMetricSeriesUseCase.Result.Kind.NOT_FOUND || calls.get() != 1) {
            throw new AssertionError("Cross-tenant catalog lookup must not reach storage");
        }
        System.out.println("QueryMetricSeriesSmoke: 9 checks passed");
    }

    private static Principal principal(Set<Permission> permissions) {
        return new Principal(new SubjectId("user-demo"), new TenantId("tenant-demo"), permissions, ResourceScope.tenantWide());
    }
}
