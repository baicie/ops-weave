import com.acme.opsweave.alerting.domain.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.api.*;
import com.acme.opsweave.incident.application.*;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

public class IncidentSmoke {
    private static int checks;
    private static final TenantId TENANT = new TenantId("incident-test");
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final EntityId ENTITY = EntityId.parse("11111111-1111-1111-1111-111111111111");
    private static final IncidentVisibility ALL = new IncidentVisibility(true, Set.of(), true, Set.of());
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    private static void fail(IncidentFailure.Code code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IncidentFailure e) { check(e.code() == code); }
    }
    private static ExternalProblem problem(TenantId tenant, String event, String host, boolean recovered, Instant observed) {
        return new ExternalProblem(tenant, "zabbix-1", event, "50", "Fixture problem " + event, 4, NOW.minusSeconds(600), observed,
            List.of(host), false, recovered ? "201" : null, recovered ? NOW.minusSeconds(300) : null);
    }
    private static Principal principal(ResourceScope scope, Set<Permission> permissions) { return new Principal(new SubjectId("reader"), TENANT, permissions, scope); }
    public static void main(String[] args) {
        var store = new InMemoryIncidentStore((tenant, source, ids) -> ids.contains("10084") ? Map.of("10084", ENTITY) : Map.of());
        var active = problem(TENANT, "101", "10084", false, NOW);
        var imported = store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(active), NOW);
        check(imported.createdIncidents() == 1); check(imported.unmappedHosts() == 0);
        UUID id = IncidentRecord.initialId(active); var first = store.find(TENANT, id, ALL).orElseThrow();
        check(first.incident().status() == IncidentStatus.OPEN); check(first.timeline().size() == 1); check(first.entityIds().equals(Set.of(ENTITY)));
        check(store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(active), NOW).createdIncidents() == 0);
        check(store.find(TENANT, id, ALL).orElseThrow().equals(first));
        var newer = problem(TENANT, "101", "10084", false, NOW.plusSeconds(1));
        check(store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(newer), NOW.plusSeconds(1)).changedIncidents() == 0);
        check(store.find(TENANT, id, ALL).orElseThrow().incident().version() == 1);
        var missing = problem(TENANT, "102", "999", false, NOW);
        check(store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(missing), NOW).unmappedHosts() == 1);
        var permissions = Set.of(Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.INCIDENT_MANAGE);
        var all = principal(ResourceScope.tenantWide(), permissions); var service = new IncidentService(new AuthorizeUseCase(), store, Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));
        check(service.page(all, null, null, 1).nextCursor() != null);
        check(service.page(all, null, service.page(all, null, null, 1).nextCursor(), 1).nextCursor() == null);
        var scoped = principal(ResourceScope.of(Set.of(ResourceRef.anyIncident(TENANT), ResourceRef.entity(TENANT, ENTITY))), permissions);
        check(service.page(scoped, null, null, 25).items().size() == 1);
        check(service.get(scoped, id).incident().id().equals(id));
        fail(NOT_FOUND, () -> service.get(scoped, IncidentRecord.initialId(missing)));
        var noEntity = principal(ResourceScope.tenantWide(), Set.of(Permission.INCIDENT_READ));
        fail(FORBIDDEN, () -> service.get(noEntity, id));
        fail(FORBIDDEN, () -> service.page(noEntity, null, null, 25));
        var otherOnly = principal(ResourceScope.of(Set.of(ResourceRef.incident(TENANT, UUID.randomUUID()), ResourceRef.entity(TENANT, ENTITY))), permissions);
        fail(FORBIDDEN, () -> service.get(otherOnly, id));
        fail(INVALID_REQUEST, () -> service.page(all, null, null, 101));
        UUID request = UUID.randomUUID(); var investigating = service.transition(all, id, 1, IncidentStatus.INVESTIGATING, request);
        check(investigating.version() == 2); check(service.transition(all, id, 1, IncidentStatus.INVESTIGATING, request).equals(investigating));
        check(service.get(all, id).timeline().size() == 2);
        fail(CONFLICT, () -> service.transition(all, id, 2, IncidentStatus.INVESTIGATING, request));
        fail(CONFLICT, () -> service.transition(all, id, 1, IncidentStatus.MITIGATED, UUID.randomUUID()));
        fail(INVALID_TRANSITION, () -> service.transition(all, id, 2, IncidentStatus.CLOSED, UUID.randomUUID()));
        check(service.transition(all, id, 2, IncidentStatus.MITIGATED, UUID.randomUUID()).version() == 3);
        fail(ACTIVE_PROBLEMS, () -> service.transition(all, id, 3, IncidentStatus.RESOLVED, UUID.randomUUID()));
        var recovered = problem(TENANT, "101", "10084", true, NOW.plusSeconds(10));
        check(store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(recovered), NOW.plusSeconds(10)).changedIncidents() == 1);
        var changed = service.get(all, id); check(changed.incident().status() == IncidentStatus.MITIGATED); check(changed.incident().version() == 4);
        check(changed.problems().getFirst().observation().state() == ExternalProblem.State.RECOVERED);
        check(changed.timeline().stream().filter(t -> t.kind() == IncidentRecord.Kind.RECOVERY).count() == 1);
        store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(active), NOW.plusSeconds(11));
        check(service.get(all, id).incident().version() == 4); check(service.get(all, id).problems().getFirst().observation().state() == ExternalProblem.State.RECOVERED);
        check(service.transition(all, id, 4, IncidentStatus.RESOLVED, UUID.randomUUID()).version() == 5);
        check(service.transition(all, id, 5, IncidentStatus.CLOSED, UUID.randomUUID()).version() == 6);
        fail(INVALID_TRANSITION, () -> service.transition(all, id, 6, IncidentStatus.INVESTIGATING, UUID.randomUUID()));
        var readOnly = principal(ResourceScope.tenantWide(), Set.of(Permission.INCIDENT_READ, Permission.ENTITY_READ));
        fail(FORBIDDEN, () -> service.transition(readOnly, id, 6, IncidentStatus.INVESTIGATING, UUID.randomUUID()));
        fail(CONFLICT, () -> store.ingest(TENANT, "zabbix-1", "zabbix-jsonrpc", List.of(active), NOW));
        var another = problem(TENANT, "103", "10084", false, NOW);
        fail(CONFLICT, () -> store.ingest(TENANT, "zabbix-1", "zabbix-jsonrpc", List.of(another, active), NOW));
        check(store.find(TENANT, IncidentRecord.initialId(another), ALL).isEmpty());
        var foreign = problem(new TenantId("foreign"), "101", "10084", false, NOW);
        check(!IncidentRecord.initialId(foreign).equals(id)); check(store.find(foreign.tenantId(), id, ALL).isEmpty());
        check(store.ingest(TENANT, "zabbix-1", "labeled-fixture", List.of(), NOW).accepted() == 0);
        check(service.get(all, IncidentRecord.initialId(missing)).problems().getFirst().observation().state() == ExternalProblem.State.ACTIVE);
        System.out.println("Incident smoke: " + checks + " checks passed");
    }
}
