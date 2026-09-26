import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public class PipelineVersionSmoke {
    private static int checks;
    public static void main(String[] args) {
        var definition = PipelineDefinition.zabbixHostV1();
        var v1 = PipelineVersion.of(definition);
        var reversed = new ArrayList<>(definition.nodes()); Collections.reverse(reversed);
        var reordered = new PipelineDefinition(definition.id(), 1, "zabbix", "host", reversed, definition.edges(), definition.errorPolicy());
        require(v1.digest().equals(PipelineVersion.of(reordered).digest()), "digest independent of node declaration order");
        invalid(() -> new PipelineVersion(definition, "sha256:" + "0".repeat(64)), "digest is verified");
        var badEdges = List.of(new PipelineEdge("source", "map"), new PipelineEdge("map", "parse"),
            new PipelineEdge("parse", "validate"), new PipelineEdge("validate", "resolve"), new PipelineEdge("resolve", "write"));
        invalid(() -> new PipelineDefinition("bad", 1, "zabbix", "host", definition.nodes(), badEdges, ErrorPolicy.FAIL_FAST), "reject reordered operations");
        var configured = new ArrayList<>(definition.nodes());
        configured.set(2, new PipelineNode("map", NodeType.MAP, Map.of("script", "forbidden")));
        invalid(() -> new PipelineDefinition("bad", 1, "zabbix", "host", configured, definition.edges(), ErrorPolicy.FAIL_FAST), "reject scripts");
        var versions = new InMemoryPipelineVersionStore();
        var raw = new InMemoryRawRecordStore();
        var runs = new InMemorySyncRunStore();
        var inventory = new InMemoryInventoryStore();
        var tenant = new TenantId("tenant-pipeline");
        var principal = new Principal(new SubjectId("pipeline-user"), tenant,
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ), ResourceScope.tenantWide());
        var service = new HostPipelineService(new AuthorizeUseCase(), versions, raw, runs, "zabbix-1");
        var v2def = nameVersion(2, ErrorPolicy.SKIP_RECORD);
        var v2 = service.publish(principal, v2def);
        require(service.publish(principal, v2def).equals(v2), "idempotent publish");
        var conflicting = new PipelineDefinition(v2def.id(), 2, "zabbix", "host", definition.nodes(), definition.edges(), ErrorPolicy.FAIL_FAST);
        code(() -> service.publish(principal, conflicting), PipelineException.Code.VERSION_CONFLICT, "immutable publish");
        require(versions.find(new TenantId("another"), "zabbix-1", v2def.id(), 2).isEmpty(), "tenant scoped version");
        require(versions.find(tenant, "another", v2def.id(), 2).isEmpty(), "source scoped version");
        var denied = new Principal(principal.subjectId(), tenant, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        code(() -> service.publish(denied, v2def), PipelineException.Code.FORBIDDEN, "publish permission");
        var sync = new IngestZabbixHostsUseCase(new AuthorizeUseCase(), new FixtureZabbixHostConnector(), inventory,
            raw, runs, versions, "labeled-fixture", "memory", "zabbix-1", "unused", 1);
        var result = sync.execute(principal, null);
        require(result.accepted() == 2, "sync baseline");
        require(versions.pinned(tenant, "zabbix-1", result.syncRunId()).orElseThrow().equals(v1.ref()), "newest version does not move default pin");
        var report = service.preview(principal, result.syncRunId(), v2def, 100);
        require(report.accepted() == 2 && report.changed() > 0, "preview compares mapping results");
        require(!report.writesPerformed() && report.dryRun(), "preview no writes");
        require(report.originalVersion().equals(v1.ref()), "original immutable version");
        require(report.targetVersion().equals(v2.ref()), "candidate digest");
        require(report.missingRaw() == 0 && !report.truncated(), "raw complete");
        var baseline = inventory.list(tenant);
        var replay = service.replay(principal, result.syncRunId(), v2.ref(), 100, true, "COMPARE_VERSION");
        require(replay.rows().equals(report.rows()), "replay and preview deterministic");
        require(inventory.list(tenant).equals(baseline), "replay inventory unchanged");
        code(() -> service.replay(principal, result.syncRunId(), v2.ref(), 100, false, "COMPARE_VERSION"), PipelineException.Code.INVALID_REQUEST, "no write replay");
        code(() -> service.replay(principal, result.syncRunId(), v2.ref(), 101, true, "COMPARE_VERSION"), PipelineException.Code.INVALID_REQUEST, "quantity limit");
        require(service.preview(principal, result.syncRunId(), v2def, 1).truncated(), "bounded sample marked partial");
        var unknown = new PipelineVersion.Ref(v2def.id(), 3, v2.digest());
        code(() -> sync.execute(principal, null, unknown), PipelineException.Code.NOT_FOUND, "draft cannot sync");
        code(() -> service.replay(principal, result.syncRunId(), new PipelineVersion.Ref(v2def.id(), 2, v1.digest()), 10, true, "COMPARE_VERSION"), PipelineException.Code.DIGEST_MISMATCH, "exact digest required");
        var syncedV2 = sync.execute(principal, null, v2.ref());
        require(versions.pinned(tenant, "zabbix-1", syncedV2.syncRunId()).orElseThrow().equals(v2.ref()), "explicit version pinned");
        require(inventory.list(tenant).stream().allMatch(e -> e.attributes().get("pipelineDigest").equals(v2.digest())), "inventory lineage digest");
        code(() -> versions.pin(tenant, "zabbix-1", syncedV2.syncRunId(), v1.ref()), PipelineException.Code.VERSION_CONFLICT, "run cannot change pin");
        require(raw.read(new TenantId("other"), "zabbix-1", result.syncRunId(), 10).records().isEmpty(), "raw tenant scope");
        require(raw.read(tenant, "other", result.syncRunId(), 10).records().isEmpty(), "raw source scope");
        var noEntity = new Principal(principal.subjectId(), tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        code(() -> service.preview(noEntity, result.syncRunId(), v2def, 10), PipelineException.Code.FORBIDDEN, "mapped entity permission");
        var running = runs.start(tenant, "zabbix-1", "host", "labeled-fixture");
        code(() -> service.preview(principal, running.id(), v2def, 10), PipelineException.Code.RUN_NOT_READY, "running snapshot blocked");
        runs.succeed(tenant, running.id(), com.acme.opsweave.integration.domain.SyncScan.OFFSET_ATTEMPT);
        code(() -> service.preview(principal, running.id(), v2def, 10), PipelineException.Code.LINEAGE_UNAVAILABLE, "legacy lineage not fabricated");
        var missingService = new HostPipelineService(new AuthorizeUseCase(), versions, (t, s, r, l) -> new RawRecordReader.Batch(List.of(), 0), runs, "zabbix-1");
        require(missingService.preview(principal, result.syncRunId(), v2def, 10).missingRaw() == 2, "missing raw is explicit");
        var oversizedService = new HostPipelineService(new AuthorizeUseCase(), versions,
            (t, s, r, l) -> new RawRecordReader.Batch(List.of(new RawRecordReader.Retained("raw-large", null)), 2), runs, "zabbix-1");
        require(oversizedService.preview(principal, result.syncRunId(), v2def, 10).oversized() == 1, "oversized raw reported");
        var invalidNameRaw = new Connector.RawRecord("1", Instant.now(), Map.of("hostid", "1", "host", "valid-name", "name", "x".repeat(300)));
        var repairService = new HostPipelineService(new AuthorizeUseCase(), versions,
            (t, s, r, l) -> new RawRecordReader.Batch(List.of(new RawRecordReader.Retained("raw-repair", invalidNameRaw)), 1), runs, "zabbix-1");
        var repaired = repairService.preview(principal, result.syncRunId(), v2def, 10);
        require(repaired.accepted() == 1 && repaired.changed() == 1 && repaired.rows().getFirst().previous() == null,
            "candidate can repair a record rejected by the original version");
        var badScope = new Principal(principal.subjectId(), tenant, principal.permissions(),
            ResourceScope.of(Set.of(ResourceRef.source(tenant, "another-source"))));
        code(() -> service.preview(badScope, result.syncRunId(), v2def, 10), PipelineException.Code.FORBIDDEN, "source scope enforced");
        var strict = service.publish(principal, nameVersion(3, ErrorPolicy.FAIL_FAST));
        Connector invalidHost = new Connector() {
            public String type() { return "zabbix"; }
            public ProbeResult probe(SourceContext c) { throw new AssertionError(); }
            public Page fetch(SourceContext c, String cursor, int limit) { return new Page(List.of(new RawRecord("1", Instant.now(), Map.of("name", "invalid"))), null, true); }
        };
        var strictSync = new IngestZabbixHostsUseCase(new AuthorizeUseCase(), invalidHost, inventory, raw, runs, versions,
            "labeled-fixture", "memory", "zabbix-1", "unused", 1).execute(principal, null, strict.ref());
        require(!strictSync.snapshotComplete() && strictSync.rejected() == 1, "fail fast stops before reconcile");
        require(service.replay(principal, strictSync.syncRunId(), strict.ref(), 10, true, "VALIDATE_MAPPING").wouldFailFast(), "failed runs remain diagnosable");
        System.out.println("Pipeline version smoke: " + checks + " checks passed");
    }
    private static PipelineDefinition nameVersion(int revision, ErrorPolicy policy) {
        var d = PipelineDefinition.zabbixHostV1(); var nodes = new ArrayList<>(d.nodes());
        nodes.set(2, new PipelineNode("map", NodeType.MAP, Map.of("displayNameField", "host")));
        return new PipelineDefinition(d.id(), revision, "zabbix", "host", nodes, d.edges(), policy);
    }
    private static void require(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static void invalid(Runnable work, String name) {
        try { work.run(); throw new AssertionError(name); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void code(Runnable work, PipelineException.Code code, String name) {
        try { work.run(); throw new AssertionError(name); } catch (PipelineException expected) { require(expected.code() == code, name); }
    }
}
