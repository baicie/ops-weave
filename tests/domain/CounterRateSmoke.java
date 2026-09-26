import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.domain.CounterRatePolicy;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.MetricSeries;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.Sample;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
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

/** Counter (SUM) metrics get an explicit rate view; a reset is marked, never shown as a negative rate. */
public final class CounterRateSmoke {
    private static final TenantId TENANT = new TenantId("tenant-counter");
    private static final EntityId ENTITY = new EntityId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static int checks = 0;

    public static void main(String[] args) {
        // A monotonic counter: consecutive deltas over the real interval.
        var monotonic = CounterRatePolicy.derive(List.of(
            sample(0, "100"), sample(10_000, "150"), sample(20_000, "200")
        ));
        require(monotonic.size() == 2, "one rate per interval");
        require("5.000000".equals(monotonic.get(0).perSecond().toPlainString()), "the first rate is per second");
        require("5.000000".equals(monotonic.get(1).perSecond().toPlainString()), "the second rate is per second");
        require(monotonic.stream().noneMatch(CounterRatePolicy.Rate::counterReset), "a monotonic counter has no reset");

        // A reset: the delta is the value counted from zero and the interval is marked.
        var reset = CounterRatePolicy.derive(List.of(
            sample(0, "100"), sample(10_000, "150"), sample(20_000, "20"), sample(30_000, "40")
        ));
        require(reset.size() == 3, "every interval keeps a rate");
        require("5.000000".equals(reset.get(0).perSecond().toPlainString()), "before the reset the delta is the difference");
        require("2.000000".equals(reset.get(1).perSecond().toPlainString()), "after a reset the counter counts from zero");
        require(reset.get(1).counterReset(), "the reset interval is marked");
        require("2.000000".equals(reset.get(2).perSecond().toPlainString()), "the next interval resumes normally");
        require(!reset.get(2).counterReset(), "only the reset interval is marked");
        require(reset.stream().noneMatch(rate -> rate.perSecond().signum() < 0), "a derived rate is never negative");

        // Duplicate or out-of-order timestamps are skipped instead of inventing an interval.
        var duplicate = CounterRatePolicy.derive(List.of(
            sample(10_000, "100"), sample(10_000, "120"), sample(20_000, "150")
        ));
        require(duplicate.size() == 1, "a zero-length interval produces no rate");
        require("3.000000".equals(duplicate.get(0).perSecond().toPlainString()), "the next interval uses the previous sample");

        // A negative counter value is invalid input: the interval is skipped, not fabricated.
        var invalid = CounterRatePolicy.derive(List.of(sample(0, "10"), sample(10_000, "-5"), sample(20_000, "5")));
        require(invalid.size() == 1, "an invalid interval is skipped");
        require("1.000000".equals(invalid.get(0).perSecond().toPlainString()), "the valid interval still yields a rate");

        // The use case derives rates for SUM definitions only; gauges stay raw.
        var counter = new QueryMetricSeriesUseCase(new AuthorizeUseCase(), (tenant, entity) -> true, definitions(MetricType.SUM),
            port(), Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC))
            .query(reader(), ENTITY, "host.cpu.usage.user", 1_000, 1_600, 100);
        require(counter.kind() == QueryMetricSeriesUseCase.Result.Kind.AVAILABLE, "a SUM query is available");
        require(MetricSeriesResult.DERIVATION_COUNTER_RATE.equals(counter.page().derivation()), "a SUM page states its derivation");
        var derived = counter.page().series().getFirst().counterRates();
        require(derived.size() == 2 && derived.get(1).counterReset(), "the derived view carries the reset interval");
        require(counter.page().series().getFirst().points().size() == 3, "raw points are preserved next to the rates");

        var gauge = new QueryMetricSeriesUseCase(new AuthorizeUseCase(), (tenant, entity) -> true, definitions(MetricType.GAUGE),
            port(), Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC))
            .query(reader(), ENTITY, "host.cpu.usage.user", 1_000, 1_600, 100);
        require(gauge.kind() == QueryMetricSeriesUseCase.Result.Kind.AVAILABLE, "a gauge query is available");
        require(gauge.page().derivation() == null, "a gauge page stays raw");
        require(gauge.page().series().getFirst().counterRates().isEmpty(), "a gauge page has no derived rates");

        System.out.println("CounterRateSmoke: " + checks + " checks passed");
    }

    private static InMemoryMetricDefinitionStore definitions(MetricType type) {
        var store = new InMemoryMetricDefinitionStore();
        store.upsert(new MetricDefinition(TENANT, "host.cpu.usage.user", "CPU User", "1", MetricValueType.DOUBLE, type, List.of("mode"), 1));
        return store;
    }

    private static InMemoryMetricQuery port() {
        return new InMemoryMetricQuery(List.of(new MetricSeries("zabbix-1", "labeled-fixture", "20001", 1, "1", Map.of("mode", "user"),
            List.of(sample(1_000_000, "100"), sample(1_010_000, "150"), sample(1_020_000, "20")))));
    }

    private static Principal reader() {
        return new Principal(new SubjectId("operator"), TENANT, Set.of(Permission.ENTITY_READ, Permission.METRIC_READ), ResourceScope.tenantWide());
    }

    private static Sample sample(long timestampMillis, String value) {
        return new Sample(timestampMillis, new BigDecimal(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }
}
