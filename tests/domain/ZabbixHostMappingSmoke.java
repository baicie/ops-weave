import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.ZabbixHostMapper;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class ZabbixHostMappingSmoke {
    public static void main(String[] args) {
        var pipeline = PipelineDefinition.zabbixHostV1();
        require(pipeline.executionOrder().size() == 6, "linear host pipeline");
        var tenant = new TenantId("tenant-demo");
        var mapper = new ZabbixHostMapper();
        var payload = Map.<String, Object>of(
            "hostid", "10084",
            "host", "zabbix-server",
            "name", "Zabbix server",
            "status", "0",
            "interfaces", java.util.List.of(Map.of("ip", "10.0.0.10", "main", "1", "type", "1"))
        );
        var first = mapper.map(
            pipeline, tenant, "zabbix-1", payload,
            Instant.parse("2026-09-21T12:00:00Z"),
            Instant.parse("2026-09-21T12:00:01Z"),
            "raw-1"
        );
        var second = mapper.map(
            pipeline, tenant, "zabbix-1", payload,
            Instant.parse("2026-09-21T13:00:00Z"),
            Instant.parse("2026-09-21T13:00:01Z"),
            "raw-2"
        );
        require(first.entity().id().equals(second.entity().id()), "repeat sync keeps entity id");
        require("Zabbix server".equals(first.entity().name()), "host name");
        require("10.0.0.10".equals(first.entity().attributes().get("ip")), "host ip");
        require("zabbix".equals(first.entity().attributes().get("source")), "source label");
        require("10084".equals(first.entity().attributes().get("hostId")), "host id");
        require(mapper.rejectReason(Map.of("name", "x")).isPresent(), "missing hostid rejected");
        var otherTenant = mapper.map(
            pipeline, new TenantId("tenant-other"), "zabbix-1", payload,
            Instant.parse("2026-09-21T12:00:00Z"),
            Instant.parse("2026-09-21T12:00:01Z"),
            "raw-3"
        );
        require(!first.entity().id().equals(otherTenant.entity().id()), "cross-tenant host identity");

        var inventory = new InMemoryInventoryStore();
        var ingest = new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixHostConnector(),
            inventory,
            new InMemoryRawRecordStore(),
            pipeline,
            "labeled-fixture",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN"
        );
        var principal = new Principal(
            new SubjectId("user-demo"),
            tenant,
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ),
            ResourceScope.tenantWide()
        );
        var outcome = ingest.execute(principal, null);
        require(outcome.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "fixture ingest completed");
        require(outcome.accepted() == 2, "two hosts accepted");
        require("labeled-fixture".equals(outcome.dataMode()), "fixture remains labeled");
        require(inventory.list(tenant).size() == 2, "entities written");
        var denied = ingest.execute(
            new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()),
            null
        );
        require(denied.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.DENIED, "sync without permission denied");
        System.out.println("Zabbix host mapping smoke: 12 checks passed");
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }
}
