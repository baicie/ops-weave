package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.inventory.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresIncidentIT extends OwnedInventoryTest {
    private static final IncidentVisibility ALL = new IncidentVisibility(true, Set.of(), true, Set.of());
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00.123456789Z");
    private final TenantId tenant = new TenantId("incident-pg-" + UUID.randomUUID());
    private final OpsweaveProperties properties = new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    private final InventoryWiring wiring = openInventory(properties);
    private ExternalProblem problem(String event, String host, boolean recovered, Instant observed) {
        return new ExternalProblem(tenant, "zabbix-1", event, "50", "Fixture problem " + event, 4, NOW.minusSeconds(600), observed,
            List.of(host), false, recovered ? "201" : null, recovered ? NOW.minusSeconds(300) : null);
    }
    private EntityId host(String external) {
        var key = new ExternalObjectKey(tenant, "zabbix-1", "host", external, "1"); var id = EntityIds.fromExternal(key);
        wiring.writer().upsert(new Entity(id, tenant, "host", "Host " + external, Lifecycle.ACTIVE, 1, NOW, Map.of()),
            new Observation(UUID.randomUUID().toString(), key, id, NOW, NOW, Map.of(), "raw-test", 1), new ExternalLink(id, key)); return id;
    }
    @Test void concurrentImportCreatesOneOccurrenceAndRecoversAfterAdapterRestart() throws Exception {
        EntityId entity = host("10084"); var problem = problem("101", "10084", false, NOW); var id = IncidentRecord.initialId(problem);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            Callable<Integer> importIt = () -> { gate.await(); return wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(problem), NOW).createdIncidents(); };
            var a = executor.submit(importIt); var b = executor.submit(importIt); gate.countDown();
            assertEquals(1, a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS));
        }
        var first = wiring.incidents().find(tenant, id, ALL).orElseThrow();
        assertEquals(Set.of(entity), first.entityIds()); assertEquals(1, first.timeline().size()); assertEquals(NOW.minusSeconds(600), first.problems().getFirst().observation().occurredAt());
        var restarted = openInventory(properties);
        assertEquals(first, restarted.incidents().find(tenant, id, ALL).orElseThrow());
        var captured=restarted.problemHistory().observations(tenant,id,ALL,new ProblemHistoryQuery(1,NOW.minusSeconds(1).getEpochSecond(),NOW.getEpochSecond()+1,NOW.plusSeconds(1),"","",null,25));
        assertEquals(1,captured.size());assertEquals(NOW,captured.getFirst().firstReceivedAt());
        var recovered = problem("101", "10084", true, NOW.plusSeconds(10));
        assertEquals(1, restarted.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(recovered), NOW.plusSeconds(10)).changedIncidents());
        var after = wiring.incidents().find(tenant, id, ALL).orElseThrow();
        assertEquals(IncidentStatus.OPEN, after.incident().status()); assertEquals(2, after.timeline().size()); assertEquals(2, after.incident().version());
        assertEquals(ExternalProblem.State.RECOVERED, after.problems().getFirst().observation().state());
        wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(problem), NOW.plusSeconds(20));
        assertEquals(ExternalProblem.State.RECOVERED, wiring.incidents().find(tenant, id, ALL).orElseThrow().problems().getFirst().observation().state());
        assertEquals(2, wiring.incidents().find(tenant, id, ALL).orElseThrow().timeline().size());
    }
    @Test void appliesEntityScopeBeforePaginationAndPreservesUnknownMapping() {
        EntityId a = host("10084"); host("10085");
        var one = problem("101", "10084", false, NOW); var two = problem("102", "10085", false, NOW); var unknown = problem("103", "999", false, NOW);
        assertEquals(1, wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(one, two, unknown), NOW).unmappedHosts());
        var scope = new IncidentVisibility(true, Set.of(), false, Set.of(a));
        var page = wiring.incidents().page(tenant, scope, IncidentStatus.OPEN, null, 1);
        assertEquals(1, page.size()); assertEquals(IncidentRecord.initialId(one), page.getFirst().id());
        assertTrue(wiring.incidents().find(tenant, IncidentRecord.initialId(two), scope).isEmpty());
        assertTrue(wiring.incidents().find(tenant, IncidentRecord.initialId(unknown), scope).isEmpty());
        assertTrue(wiring.incidents().find(new TenantId("other"), IncidentRecord.initialId(one), ALL).isEmpty());
        assertEquals(3, wiring.incidents().page(tenant, ALL, null, null, 25).size());
        var first = wiring.incidents().page(tenant, ALL, null, null, 1); assertEquals(2, first.size());
        var rest = wiring.incidents().page(tenant, ALL, null, first.getFirst().id(), 25); assertEquals(2, rest.size());
        assertTrue(wiring.incidents().find(tenant, IncidentRecord.initialId(unknown), ALL).orElseThrow().hasUnmappedHosts());
        host("999"); wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(unknown), NOW);
        assertFalse(wiring.incidents().find(tenant, IncidentRecord.initialId(unknown), ALL).orElseThrow().hasUnmappedHosts());
    }
    @Test void conflictRollsBackWholePageAndEmptyReadNeverClosesProblems() {
        var a = problem("101", "10084", false, NOW); var b = problem("102", "10084", false, NOW);
        wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(a), NOW);
        var failure = assertThrows(IncidentFailure.class, () -> wiring.incidents().ingest(tenant, "zabbix-1", "zabbix-jsonrpc", List.of(a, b), NOW));
        assertEquals(IncidentFailure.Code.CONFLICT, failure.code());
        assertTrue(wiring.incidents().find(tenant, IncidentRecord.initialId(b), ALL).isEmpty());
        assertEquals(0, wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(), NOW).accepted());
        assertEquals(ExternalProblem.State.ACTIVE, wiring.incidents().find(tenant, IncidentRecord.initialId(a), ALL).orElseThrow().problems().getFirst().observation().state());
    }
    @Test void concurrentManualTransitionUsesVersionAndStableIdempotency() throws Exception {
        host("10084"); var a = problem("101", "10084", true, NOW); var id = IncidentRecord.initialId(a);
        wiring.incidents().ingest(tenant, "zabbix-1", "labeled-fixture", List.of(a), NOW);
        UUID firstKey = UUID.randomUUID(); UUID secondKey = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (var key : List.of(firstKey, secondKey)) results.add(executor.submit(() -> {
                gate.await(); try { wiring.incidents().transition(tenant, id, ALL, 1, IncidentStatus.INVESTIGATING, key, "operator", NOW.plusSeconds(1)); return "OK"; }
                catch (IncidentFailure failure) { return failure.code().name(); }
            })); gate.countDown();
            assertEquals(Set.of("OK", "CONFLICT"), Set.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)));
        }
        var current = wiring.incidents().find(tenant, id, ALL).orElseThrow();
        var appliedKey = current.timeline().stream().filter(t -> t.kind() == IncidentRecord.Kind.STATUS_CHANGE).findFirst().orElseThrow().id();
        assertEquals(2, openInventory(properties).incidents().transition(tenant, id, ALL, 1, IncidentStatus.INVESTIGATING, appliedKey, "operator", NOW.plusSeconds(2)).version());
        assertEquals(3, current.timeline().size());
        assertEquals(IncidentFailure.Code.CONFLICT, assertThrows(IncidentFailure.class, () -> wiring.incidents().transition(tenant, id, ALL, 1,
            IncidentStatus.MITIGATED, appliedKey, "operator", NOW.plusSeconds(2))).code());
    }
    @Test void mergeAndSplitMoveRecoveryOwnershipAcrossAdapterRestart() {
        host("10084"); host("10085"); var a=problem("401","10084",false,NOW); var b=problem("402","10085",false,NOW);
        var store=wiring.incidents(); store.ingest(tenant,"zabbix-1","labeled-fixture",List.of(a,b),NOW);
        var aid=IncidentRecord.initialId(a); var bid=IncidentRecord.initialId(b);
        var merge=new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.MERGE,aid,1,bid,1,List.of(),null,"Fixture operator review");
        var merged=store.reorganize(tenant,merge,ALL,"operator",NOW.plusSeconds(1));
        assertEquals(1,store.page(tenant,ALL,null,null,25).size()); assertEquals(bid,store.find(tenant,aid,ALL).orElseThrow().organization().mergedInto());
        var restored=openInventory(properties).incidents(); assertEquals(merged,restored.reorganize(tenant,merge,ALL,"operator",NOW.plusSeconds(2)));
        var imported=restored.ingest(tenant,"zabbix-1","labeled-fixture",List.of(problem("401","10084",true,NOW.plusSeconds(3)),problem("402","10085",true,NOW.plusSeconds(3))),NOW.plusSeconds(3));
        assertEquals(0,imported.createdIncidents()); assertEquals(1,imported.changedIncidents());
        var combined=store.find(tenant,bid,ALL).orElseThrow(); assertEquals(4,combined.incident().version()); assertEquals(4,combined.timeline().size());
        assertEquals(ExternalProblem.State.ACTIVE,store.find(tenant,aid,ALL).orElseThrow().problems().getFirst().observation().state());
        var split=new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.SPLIT,bid,4,UUID.randomUUID(),0,List.of(new IncidentReorganization.ProblemKey("zabbix-1","401")),"Separate fixture investigation","Human reviewed separate scope");
        var divided=restored.reorganize(tenant,split,ALL,"operator",NOW.plusSeconds(4));
        var child=openInventory(properties).incidents().find(tenant,split.targetIncidentId(),ALL).orElseThrow();
        assertEquals(NOW,child.problems().getFirst().firstReceivedAt()); assertEquals(2,child.timeline().size()); assertEquals(IncidentStatus.OPEN,child.incident().status());
        assertEquals(divided,store.reorganization(tenant,split.requestKey(),ALL).orElseThrow());
        assertTrue(store.reorganization(new TenantId("other"),split.requestKey(),ALL).isEmpty());
        assertTrue(store.reorganization(tenant,split.requestKey(),new IncidentVisibility(false,Set.of(bid),true,Set.of())).isEmpty());
        var history=store.reorganizations(tenant,bid,ALL,null,1);
        assertEquals(2,history.size()); // Store returns limit+1 for a stable UUID continuation.
        var ordered=List.of(merged,divided).stream().sorted(Comparator.comparing(r->r.request().requestKey().toString())).toList();
        assertEquals(ordered,history);
        assertEquals(List.of(ordered.getLast()),store.reorganizations(tenant,bid,ALL,ordered.getFirst().request().requestKey(),1));
        var splitOnly=new IncidentVisibility(false,Set.of(bid,split.targetIncidentId()),true,Set.of());
        assertEquals(List.of(divided),store.reorganizations(tenant,bid,splitOnly,null,1));
        assertTrue(store.reorganizations(tenant,bid,new IncidentVisibility(false,Set.of(bid),true,Set.of()),null,1).isEmpty());
        assertTrue(store.reorganizations(new TenantId("other"),bid,ALL,null,1).isEmpty());
        assertThrows(IncidentFailure.class,()->store.reorganize(tenant,split,ALL,"other",NOW.plusSeconds(5)));
        restored.ingest(tenant,"zabbix-1","labeled-fixture",List.of(problem("401","10084",true,NOW.plusSeconds(8))),NOW.plusSeconds(8));
        assertEquals(NOW.plusSeconds(8),store.find(tenant,split.targetIncidentId(),ALL).orElseThrow().problems().getFirst().lastReceivedAt());
        assertEquals("402",store.find(tenant,bid,ALL).orElseThrow().problems().getFirst().observation().problemEventId());
    }
    @Test void concurrentSameReorganizationCreatesOneReceipt() throws Exception {
        var a=problem("501","10084",false,NOW);var b=problem("502","10084",false,NOW);wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(a,b),NOW);
        var request=new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.MERGE,IncidentRecord.initialId(a),1,IncidentRecord.initialId(b),1,List.of(),null,"Concurrent fixture");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<IncidentReorganization.Receipt>>();
            for(int i=0;i<4;i++)results.add(workers.submit(()->{gate.await();return wiring.incidents().reorganize(tenant,request,ALL,"operator",NOW.plusSeconds(1));}));gate.countDown();
            var first=results.getFirst().get(15,TimeUnit.SECONDS);for(var result:results)assertEquals(first,result.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,wiring.incidents().find(tenant,request.targetIncidentId(),ALL).orElseThrow().incident().version());
    }
    @Test void missingOwnershipRollsBackBothAggregatesAndReceipt() throws Exception {
        var a=problem("601","10084",false,NOW);var b=problem("602","10084",false,NOW);var store=wiring.incidents();store.ingest(tenant,"zabbix-1","labeled-fixture",List.of(a,b),NOW);
        var aid=IncidentRecord.initialId(a);var bid=IncidentRecord.initialId(b);var beforeSource=store.find(tenant,aid,ALL).orElseThrow();var beforeTarget=store.find(tenant,bid,ALL).orElseThrow();
        // Deliberately corrupt only this test tenant's occurrence index to force a failure after aggregate updates.
        // Deliberately corrupt the occurrence index; remove its dependent retained observations first.
        try(var connection=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var statement=connection.prepareStatement("DELETE FROM alerting.problem_observation WHERE tenant_id=? AND problem_event_id='601'")) {statement.setString(1,tenant.value());assertEquals(1,statement.executeUpdate());}
        try(var connection=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var statement=connection.prepareStatement("DELETE FROM alerting.external_problem WHERE tenant_id=? AND problem_event_id='601'")) {statement.setString(1,tenant.value());assertEquals(1,statement.executeUpdate());}
        var request=new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.MERGE,aid,1,bid,1,List.of(),null,"Rollback fixture");
        assertThrows(IncidentFailure.class,()->store.reorganize(tenant,request,ALL,"operator",NOW.plusSeconds(1)));
        assertEquals(beforeSource,store.find(tenant,aid,ALL).orElseThrow());assertEquals(beforeTarget,store.find(tenant,bid,ALL).orElseThrow());assertTrue(store.reorganization(tenant,request.requestKey(),ALL).isEmpty());
    }
    private ProblemHistoryQuery history(long version,Instant cutoff,UUID after,int limit){return new ProblemHistoryQuery(version,NOW.minusSeconds(60).getEpochSecond(),NOW.plusSeconds(60).getEpochSecond(),cutoff,"","",after,limit);}
    @Test void historicalTenantUsesTheSame128CharacterBoundaryAsTheIncident() {
        var longTenant=new TenantId("history-"+UUID.randomUUID()+"x".repeat(84));assertEquals(128,longTenant.value().length());
        var problem=new ExternalProblem(longTenant,"zabbix-1","741","50","Bounded tenant fixture",3,NOW.minusSeconds(1),NOW,List.of(),false,null,null);
        wiring.incidents().ingest(longTenant,"zabbix-1","labeled-fixture",List.of(problem),NOW.plusSeconds(61));
        assertEquals(1,wiring.problemHistory().observations(longTenant,IncidentRecord.initialId(problem),ALL,history(1,NOW.plusSeconds(61),null,25)).size());
    }
    @Test void immutableHistoryKeepsRawRecoveryOmissionsAndNanosecondCutoff() {
        host("10084");var first=problem("701","10084",false,NOW);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(61);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received.plusSeconds(1));
        assertTrue(wiring.problemHistory().observations(tenant,id,ALL,history(1,received.minusNanos(1),null,25)).isEmpty());
        var capture=wiring.problemHistory().observations(tenant,id,ALL,history(1,received,null,25));assertEquals(1,capture.size());assertEquals(received,capture.getFirst().firstReceivedAt());
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(problem("701","10084",true,NOW.plusSeconds(10))),received.plusSeconds(2));
        var omitted=problem("701","10084",false,NOW.plusSeconds(20));wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(omitted),received.plusSeconds(3));
        var late=problem("701","10084",false,NOW.minusNanos(1));wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(late),received.plusSeconds(4));
        var restarted=openInventory(properties);var all=restarted.problemHistory().observations(tenant,id,ALL,history(2,received.plusSeconds(5),null,25));assertEquals(4,all.size());
        assertTrue(all.stream().anyMatch(e->e.observation().equals(omitted)&&e.observation().state()==ExternalProblem.State.ACTIVE));
        assertEquals(ExternalProblem.State.RECOVERED,restarted.incidents().find(tenant,id,ALL).orElseThrow().problems().getFirst().observation().state());
        assertEquals(all.subList(1,4),restarted.problemHistory().observations(tenant,id,ALL,history(2,received.plusSeconds(5),all.getFirst().id(),25)));
        assertEquals(2,restarted.problemHistory().observations(tenant,id,ALL,history(2,received.plusSeconds(5),null,1)).size());
        assertEquals(IncidentFailure.Code.CONFLICT,assertThrows(IncidentFailure.class,()->restarted.problemHistory().observations(tenant,id,ALL,history(1,received.plusSeconds(5),null,25))).code());
    }
    @Test void historyConflictRollsBackProjectionAndOtherOccurrence() {
        host("10084");var first=problem("711","10084",false,NOW);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(61);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(problem("711","10084",true,NOW.plusSeconds(10))),received.plusSeconds(1));
        var before=wiring.incidents().find(tenant,id,ALL).orElseThrow();var other=problem("712","10084",false,NOW);
        assertEquals(IncidentFailure.Code.CONFLICT,assertThrows(IncidentFailure.class,()->wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(other,problem("711","10084",true,NOW)),received.plusSeconds(2))).code());
        assertEquals(before,wiring.incidents().find(tenant,id,ALL).orElseThrow());assertTrue(wiring.incidents().find(tenant,IncidentRecord.initialId(other),ALL).isEmpty());
        assertEquals(2,wiring.problemHistory().observations(tenant,id,ALL,history(2,received.plusSeconds(2),null,25)).size());
    }
    @Test void historicalScopePrecedesLimitAndHistoryFollowsCurrentOwner() {
        var first=problem("721","10084",false,NOW);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(61);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received);
        var entity=host("10084");var scope=new IncidentVisibility(true,Set.of(),false,Set.of(entity));
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received.plusSeconds(1));
        assertTrue(wiring.problemHistory().observations(tenant,id,scope,history(2,received.plusSeconds(1),null,1)).isEmpty());
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(problem("721","10084",false,NOW.plusSeconds(1))),received.plusSeconds(2));
        assertEquals(1,wiring.problemHistory().observations(tenant,id,scope,history(2,received.plusSeconds(2),null,1)).size());
        var other=problem("722","10084",false,NOW);var target=IncidentRecord.initialId(other);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(other),received);
        wiring.incidents().reorganize(tenant,new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.MERGE,id,2,target,1,List.of(),null,"History ownership test"),ALL,"operator",received.plusSeconds(3));
        assertTrue(wiring.problemHistory().observations(tenant,id,ALL,history(3,received.plusSeconds(3),null,25)).isEmpty());
        assertEquals(3,wiring.problemHistory().observations(tenant,target,ALL,history(2,received.plusSeconds(3),null,25)).size());
        assertEquals(2,wiring.problemHistory().observations(tenant,target,scope,history(2,received.plusSeconds(3),null,1)).size());
        assertThrows(IncidentFailure.class,()->wiring.problemHistory().observations(new TenantId("foreign"),target,ALL,history(2,received.plusSeconds(3),null,25)));
    }
    @Test void corruptedHistoricalScopeMetadataFailsClosed() throws Exception {
        host("10084");var first=problem("731","10084",false,NOW);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(61);
        wiring.incidents().ingest(tenant,"zabbix-1","labeled-fixture",List.of(first),received);
        try(var connection=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var statement=connection.prepareStatement("UPDATE alerting.problem_observation SET has_unmapped=true WHERE tenant_id=?")){statement.setString(1,tenant.value());assertEquals(1,statement.executeUpdate());}
        assertEquals(IncidentFailure.Code.UNAVAILABLE,assertThrows(IncidentFailure.class,()->wiring.problemHistory().observations(tenant,id,ALL,history(1,received,null,25))).code());
    }
}
