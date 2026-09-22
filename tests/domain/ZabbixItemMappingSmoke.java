import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import com.acme.opsweave.telemetry.domain.ExternalMetricMapping;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricOrigin;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.util.Map;
import java.util.Set;

public final class ZabbixItemMappingSmoke {
    public static void main(String[] args) {
        var tenant = new TenantId("tenant-demo");
        var mapper = new ZabbixItemMapper();
        var payload = Map.<String, Object>of(
            "itemid", "20001",
            "key_", "system.cpu.util[,user]",
            "name", "CPU user time",
            "value_type", "0",
            "units", "%",
            "hostid", "10084"
        );
        var mapped = mapper.map(tenant, "zabbix-1", payload);
        require("host.cpu.usage.user".equals(mapped.name()), "cpu user metric name");
        require("CPU user time".equals(mapped.displayName()), "display name comes from the item");
        require("host".equals(mapped.entityType()), "metric applies to hosts");
        require("1".equals(mapped.unit()), "platform unit is a ratio");
        require(mapped.valueType() == MetricValueType.DOUBLE, "zabbix float stays numeric");
        require(mapped.metricType() == MetricType.GAUGE, "cpu utilization is a gauge");
        require("user".equals(mapped.dimensions().get("mode")), "user mode is a dimension");
        require(mapped.origin() == MetricOrigin.SOURCE, "origin is the source");
        require("20001".equals(mapped.externalMapping().externalId()), "item id is the external id");
        require("10084".equals(mapped.externalMapping().hostExternalId()), "host id stays on the mapping");
        require("multiply:0.01".equals(mapped.externalMapping().valueTransform()), "percent to ratio is recorded");
        require(mapped.id().equals(mapper.map(tenant, "zabbix-1", payload).id()), "repeat mapping keeps metric id");
        require(mapper.rejectReason(Map.of("itemid", "9", "key_", "net.if.in", "hostid", "1")).orElse("").equals("unmapped item key"), "unknown key rejected");
        require(mapper.rejectReason(Map.of("key_", "system.cpu.util[,user]")).isPresent(), "missing itemid rejected");

        var definitions = new InMemoryMetricDefinitionStore();
        definitions.upsert(stale(tenant, "29999"));
        definitions.upsert(stale(tenant, "20002"));
        var principal = new Principal(
            new SubjectId("user-demo"), tenant, Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ), ResourceScope.tenantWide()
        );
        var outcome = ingest(new FixtureZabbixItemConnector(), definitions, 1).execute(principal, null);
        require(outcome.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "item scan completed");
        require(outcome.pages() == 2, "two item pages");
        require(outcome.accepted() == 1, "only the mapped cpu item is accepted");
        require(outcome.rejected() == 1, "unmapped item is rejected");
        require(outcome.retired() == 1, "absent item definition retires");
        require("offset-scan-attempt".equals(outcome.scanConsistency()), "item scan is an offset attempt");
        require(lifecycle(definitions, tenant, "20001") == MetricLifecycle.ACTIVE, "mapped item stays active");
        require(lifecycle(definitions, tenant, "20002") == MetricLifecycle.ACTIVE, "present unmapped item is not retired");
        require(lifecycle(definitions, tenant, "29999") == MetricLifecycle.INACTIVE, "missing item becomes inactive");
        var cpu = definitions.list(tenant).stream().filter(item -> "20001".equals(item.externalMapping().externalId())).findFirst().orElseThrow();
        require("host.cpu.usage.user".equals(cpu.name()), "stored name");

        var denied = ingest(new FixtureZabbixItemConnector(), new InMemoryMetricDefinitionStore(), 1).execute(
            new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.METRIC_READ), ResourceScope.tenantWide()),
            null
        );
        require(denied.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.DENIED, "item sync without source.sync is denied");
        var hidden = new ListMetricDefinitionsUseCase(new AuthorizeUseCase(), definitions).list(
            new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide())
        );
        require(hidden.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN, "listing definitions requires metric.read");

        var kept = new InMemoryMetricDefinitionStore();
        kept.upsert(stale(tenant, "29999"));
        var failed = ingest(new FailingSecondPage(), kept, 1).execute(principal, null);
        require("SOURCE_FETCH_FAILED".equals(failed.reasonCode()), "item fetch failure code");
        require(failed.pages() == 1 && failed.accepted() == 1, "failed item scan keeps partial counters");
        require(lifecycle(kept, tenant, "29999") == MetricLifecycle.ACTIVE, "failed item scan does not retire");
        require(lifecycle(kept, tenant, "20001") == MetricLifecycle.ACTIVE, "page written before failure stays");
        System.out.println("Zabbix item mapping smoke: 30 checks passed");
    }

    private static IngestZabbixItemsUseCase ingest(Connector connector, InMemoryMetricDefinitionStore definitions, int pageSize) {
        return new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(),
            connector,
            definitions,
            new InMemoryRawRecordStore(),
            new InMemorySyncRunStore(),
            "labeled-fixture",
            "memory",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            pageSize
        );
    }

    private static MetricDefinition stale(TenantId tenant, String itemId) {
        String id = new ZabbixItemMapper().map(tenant, "zabbix-1", Map.of(
            "itemid", itemId,
            "key_", "system.cpu.util[,user]",
            "name", "stale",
            "value_type", "0",
            "units", "%",
            "hostid", "10084"
        )).id();
        return new MetricDefinition(
            id, tenant, "stale.metric", "stale", "host", "1", MetricValueType.DOUBLE, MetricType.GAUGE,
            Map.of(), MetricOrigin.SOURCE,
            new ExternalMetricMapping("zabbix", "zabbix-1", itemId, "stale.key", "10084", "%", "identity", 1),
            MetricLifecycle.ACTIVE, 1
        );
    }

    private static MetricLifecycle lifecycle(InMemoryMetricDefinitionStore store, TenantId tenant, String itemId) {
        String id = new ZabbixItemMapper().map(tenant, "zabbix-1", Map.of(
            "itemid", itemId, "key_", "system.cpu.util[,user]", "name", "x", "value_type", "0", "units", "%", "hostid", "1"
        )).id();
        return store.find(tenant, id).orElseThrow().lifecycle();
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }

    private static final class FailingSecondPage implements Connector {
        private int calls;

        @Override
        public String type() {
            return "zabbix";
        }

        @Override
        public ProbeResult probe(SourceContext source) {
            return new ProbeResult(false, "test");
        }

        @Override
        public Page fetch(SourceContext source, String cursor, int limit) {
            if (++calls > 1) {
                throw new IllegalStateException("page failed");
            }
            return new FixtureZabbixItemConnector().fetch(source, cursor, limit);
        }
    }
}
