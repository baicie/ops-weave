import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.MappingDocumentParser;
import com.acme.opsweave.integration.domain.MappingRegistry;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ZabbixItemMappingSmoke {
    public static void main(String[] args) throws Exception {
        var tenant = new TenantId("tenant-demo");
        String yaml = Files.readString(Path.of("extensions/mappings/zabbix-cpu-user.yaml"));
        String mapperSource = Files.readString(Path.of(
            "modules/integration/src/main/java/com/acme/opsweave/integration/domain/ZabbixItemMapper.java"
        ));
        var mapping = MappingDocumentParser.parse(yaml);
        var registry = new MappingRegistry(List.of(mapping));
        var mapper = new ZabbixItemMapper(registry);
        require(!mapperSource.contains(mapping.itemKeyExact()), "mapper does not contain the item key");
        require(!mapperSource.contains(mapping.metricKey()), "mapper does not contain the metric key");
        var payload = Map.<String, Object>of(
            "itemid", "20001",
            "key_", mapping.itemKeyExact(),
            "name", "CPU user time",
            "value_type", "0",
            "units", "%",
            "hostid", "10084"
        );
        var mapped = mapper.map(tenant, "zabbix-1", payload);
        require(mapping.metricKey().equals(mapped.definition().metricKey()), "definition uses the configured metric");
        require(mapping.displayName().equals(mapped.definition().displayName()), "display name comes from the mapping");
        require(!"CPU user time".equals(mapped.definition().displayName()), "item name is not the catalog name");
        require(mapping.unit().equals(mapped.definition().unit()), "platform unit comes from the mapping");
        require(mapped.definition().valueType() == MetricValueType.DOUBLE, "configured value type is double");
        require(mapped.definition().metricType() == MetricType.GAUGE, "configured kind is a gauge");
        require(mapped.definition().dimensionSchema().equals(List.of("mode")), "dimension schema comes from the mapping");
        require("20001".equals(mapped.binding().externalItemId()), "item id stays on the binding");
        require("10084".equals(mapped.binding().hostExternalId()), "host id stays on the binding");
        require("user".equals(mapped.binding().fixedDimensions().get("mode")), "fixed dimension comes from the mapping");
        require(mapping.valueTransform().equals(mapped.binding().valueTransform()), "transform comes from the mapping");
        require(mapped.binding().entityId().equals(EntityIds.fromExternal(
            new ExternalObjectKey(tenant, "zabbix-1", "host", "10084", "1")
        )), "binding points at the host entity");
        var otherHost = mapper.map(tenant, "zabbix-1", Map.of(
            "itemid", "30001",
            "key_", mapping.itemKeyExact(),
            "name", "CPU user time",
            "value_type", "0",
            "units", "%",
            "hostid", "10085"
        ));
        require(mapped.definition().metricKey().equals(otherHost.definition().metricKey()), "two hosts share one definition");
        require(!mapped.binding().externalItemId().equals(otherHost.binding().externalItemId()), "each item has its own binding");
        require(!mapped.binding().entityId().equals(otherHost.binding().entityId()), "bindings point at different hosts");
        var shared = new InMemoryMetricDefinitionStore();
        shared.upsert(mapped.definition());
        shared.upsert(mapped.binding());
        shared.upsert(otherHost.definition());
        shared.upsert(otherHost.binding());
        require(shared.list(tenant).size() == 1, "catalog keeps a single definition");
        require(shared.listBindings(tenant).size() == 2, "catalog keeps both bindings");
        require(mapper.rejectReason(Map.of("itemid", "9", "key_", "net.if.in", "hostid", "1", "value_type", "0")).orElse("")
            .equals("unmapped item key"), "unknown key rejected");
        require(mapper.rejectReason(Map.of("key_", mapping.itemKeyExact())).isPresent(), "missing itemid rejected");
        require(mapper.rejectReason(Map.of(
            "itemid", "9", "key_", mapping.itemKeyExact(), "hostid", "1", "value_type", "1"
        )).orElse("").equals("item value type does not match mapping"), "non numeric item rejected");

        var definitions = new InMemoryMetricDefinitionStore();
        seed(definitions, tenant, "29999");
        seed(definitions, tenant, "20002");
        var principal = new Principal(
            new SubjectId("user-demo"), tenant, Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ), ResourceScope.tenantWide()
        );
        var outcome = ingest(registry, new FixtureZabbixItemConnector(), definitions, 1).execute(principal, null);
        require(outcome.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "item scan completed");
        require(outcome.pages() == 2, "two item pages");
        require(outcome.accepted() == 1, "only the mapped cpu item is accepted");
        require(outcome.rejected() == 1, "unmapped item is rejected");
        require(outcome.retired() == 1, "absent item binding retires");
        require("itemid-watermark-snapshot".equals(outcome.scanConsistency()),
            "the fixture item walk completes as a bounded itemid-watermark snapshot");
        require(lifecycle(definitions, tenant, "20001") == MetricLifecycle.ACTIVE, "mapped item stays active");
        require(lifecycle(definitions, tenant, "20002") == MetricLifecycle.ACTIVE, "present unmapped item is not retired");
        require(lifecycle(definitions, tenant, "29999") == MetricLifecycle.INACTIVE, "missing item becomes inactive");
        require(definitions.find(tenant, mapping.metricKey()).isPresent(), "catalog definition is stored");
        require(definitions.find(tenant, "stale.metric").isPresent(), "retiring a binding keeps the catalog entry");
        long catalogEntries = definitions.list(tenant).stream().filter(item -> mapping.metricKey().equals(item.metricKey())).count();
        require(catalogEntries == 1, "mapped items do not duplicate the definition");

        var denied = ingest(registry, new FixtureZabbixItemConnector(), new InMemoryMetricDefinitionStore(), 1).execute(
            new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.METRIC_READ), ResourceScope.tenantWide()),
            null
        );
        require(denied.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.DENIED, "item sync without source.sync is denied");
        var reader = new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        var hidden = new ListMetricDefinitionsUseCase(new AuthorizeUseCase(), definitions).list(reader);
        require(hidden.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN, "listing definitions requires metric.read");
        var hiddenBindings = new ListMetricDefinitionsUseCase(new AuthorizeUseCase(), definitions).listBindings(reader);
        require(hiddenBindings.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN, "listing bindings requires metric.read");

        var kept = new InMemoryMetricDefinitionStore();
        seed(kept, tenant, "29999");
        var failed = ingest(registry, new FailingSecondPage(), kept, 1).execute(principal, null);
        require("SOURCE_FETCH_FAILED".equals(failed.reasonCode()), "item fetch failure code");
        require(failed.pages() == 1 && failed.accepted() == 1, "failed item scan keeps partial counters");
        require(lifecycle(kept, tenant, "29999") == MetricLifecycle.ACTIVE, "failed item scan does not retire");
        require(lifecycle(kept, tenant, "20001") == MetricLifecycle.ACTIVE, "page written before failure stays");
        System.out.println("Zabbix item mapping smoke: 41 checks passed");
    }

    private static IngestZabbixItemsUseCase ingest(
        MappingRegistry mappings, Connector connector, InMemoryMetricDefinitionStore definitions, int pageSize
    ) {
        var inventory = new com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore();
        return new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(),
            connector,
            mappings,
            inventory,
            new com.acme.opsweave.integration.infrastructure.InMemorySourceItemWrites(inventory, definitions),
            new InMemoryRawRecordStore(),
            new InMemorySyncRunStore(),
            "labeled-fixture",
            "memory",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            pageSize
        );
    }

    private static void seed(InMemoryMetricDefinitionStore store, TenantId tenant, String itemId) {
        store.upsert(new MetricDefinition(
            tenant, "stale.metric", "Stale", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 1
        ));
        store.upsert(new MetricBinding(
            tenant, "zabbix", "zabbix-1", itemId,
            EntityIds.fromExternal(new ExternalObjectKey(tenant, "zabbix-1", "host", "10084", "1")),
            "10084", "stale.metric", Map.of("mode", "idle"), "%", "identity", 1, MetricLifecycle.ACTIVE, 1
        ));
    }

    private static MetricLifecycle lifecycle(InMemoryMetricDefinitionStore store, TenantId tenant, String itemId) {
        return store.findBinding(tenant, "zabbix-1", itemId).orElseThrow().lifecycle();
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
