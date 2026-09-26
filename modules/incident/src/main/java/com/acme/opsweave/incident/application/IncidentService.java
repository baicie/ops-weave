package com.acme.opsweave.incident.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.api.IncidentStore;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

public final class IncidentService {
    private final AuthorizationService authorization;
    private final IncidentStore store;
    private final Clock clock;
    public IncidentService(AuthorizationService authorization, IncidentStore store, Clock clock) { this.authorization = authorization; this.store = store; this.clock = clock; }
    public IncidentRecord get(Principal principal, UUID id) {
        require(principal, ResourceRef.incident(principal.tenantId(), id), Permission.INCIDENT_READ);
        var visibility = visibility(principal);
        var result = store.find(principal.tenantId(), id, visibility).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
        if (!result.incident().tenantId().equals(principal.tenantId()) || !result.incident().id().equals(id) || !visibility.includes(result)) throw new IncidentFailure(UNAVAILABLE);
        for (var entity : result.entityIds()) require(principal, ResourceRef.entity(principal.tenantId(), entity), Permission.ENTITY_READ);
        return result;
    }
    public Page page(Principal principal, IncidentStatus status, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IncidentFailure(INVALID_REQUEST);
        var fetched = store.page(principal.tenantId(), visibility(principal), status, after, limit);
        if (fetched.size() > limit + 1) throw new IncidentFailure(UNAVAILABLE);
        String previous = after == null ? "" : after.toString();
        for (var header : fetched) {
            if (!header.tenantId().equals(principal.tenantId()) || header.id().toString().compareTo(previous) <= 0 || (status != null && header.status() != status)) throw new IncidentFailure(UNAVAILABLE);
            require(principal, ResourceRef.incident(principal.tenantId(), header.id()), Permission.INCIDENT_READ); previous = header.id().toString();
        }
        var items = fetched.stream().limit(limit).toList();
        return new Page(items, fetched.size() > limit ? items.getLast().id() : null);
    }
    public IncidentStore.TransitionResult transition(Principal principal, UUID id, long expected, IncidentStatus target, UUID requestKey) {
        if (expected < 1 || expected > 9007199254740991L || target == null || requestKey == null) throw new IncidentFailure(INVALID_REQUEST);
        require(principal, ResourceRef.incident(principal.tenantId(), id), Permission.INCIDENT_MANAGE);
        get(principal, id); // Also requires current read and entity authorization; store rechecks scope inside its transaction.
        return store.transition(principal.tenantId(), id, visibility(principal), expected, target, requestKey, principal.subjectId().value(), clock.instant());
    }
    public IncidentVisibility visibility(Principal principal) {
        if (!principal.has(Permission.INCIDENT_READ) || !principal.has(Permission.ENTITY_READ)) throw new IncidentFailure(FORBIDDEN);
        boolean allIncidents = authorization.authorize(principal, ResourceRef.anyIncident(principal.tenantId()), Permission.INCIDENT_READ).allowed();
        boolean allEntities = authorization.authorize(principal, ResourceRef.anyEntity(principal.tenantId()), Permission.ENTITY_READ).allowed();
        var incidents = new HashSet<UUID>(); var entities = new HashSet<EntityId>();
        for (var ref : principal.resourceScope().allowedResources()) {
            if (!ref.tenantId().equals(principal.tenantId()) || ref.id().equals("*")) continue;
            try {
                if (!allIncidents && ref.type().equals("incident") && authorization.authorize(principal, ref, Permission.INCIDENT_READ).allowed()) incidents.add(UUID.fromString(ref.id()));
                if (!allEntities && ref.type().equals("entity") && authorization.authorize(principal, ref, Permission.ENTITY_READ).allowed()) entities.add(EntityId.parse(ref.id()));
            } catch (IllegalArgumentException invalidScope) { throw new IncidentFailure(UNAVAILABLE); }
        }
        return new IncidentVisibility(allIncidents, incidents, allEntities, entities);
    }
    public IncidentReorganization.Receipt reorganize(Principal principal, IncidentReorganization.Request request) {
        require(principal,ResourceRef.incident(principal.tenantId(),request.sourceIncidentId()),Permission.INCIDENT_MANAGE);
        require(principal,ResourceRef.incident(principal.tenantId(),request.targetIncidentId()),Permission.INCIDENT_MANAGE);
        require(principal,ResourceRef.incident(principal.tenantId(),request.targetIncidentId()),Permission.INCIDENT_READ);
        get(principal,request.sourceIncidentId());
        if (request.kind()==IncidentReorganization.Kind.MERGE) get(principal,request.targetIncidentId());
        return store.reorganize(principal.tenantId(),request,visibility(principal),principal.subjectId().value(),clock.instant());
    }
    public IncidentReorganization.Receipt reorganization(Principal principal, UUID key) {
        var value=store.reorganization(principal.tenantId(),key,visibility(principal)).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
        get(principal,value.request().sourceIncidentId()); get(principal,value.request().targetIncidentId()); return value;
    }
    public OrganizationPage reorganizations(Principal principal, UUID incidentId, UUID after, int limit) {
        if(limit<1||limit>25)throw new IncidentFailure(INVALID_REQUEST);get(principal,incidentId);
        var records=store.reorganizations(principal.tenantId(),incidentId,visibility(principal),after,limit);
        if(records.size()>limit+1)throw new IncidentFailure(UNAVAILABLE);
        String previous=after==null?"":after.toString();
        for(var r:records){
            if((!r.request().sourceIncidentId().equals(incidentId)&&!r.request().targetIncidentId().equals(incidentId))||r.request().requestKey().toString().compareTo(previous)<=0)throw new IncidentFailure(UNAVAILABLE);
            require(principal,ResourceRef.incident(principal.tenantId(),r.request().sourceIncidentId()),Permission.INCIDENT_READ);
            require(principal,ResourceRef.incident(principal.tenantId(),r.request().targetIncidentId()),Permission.INCIDENT_READ);previous=r.request().requestKey().toString();
        }
        var page=records.stream().limit(limit).toList();return new OrganizationPage(page,records.size()>limit?page.getLast().request().requestKey():null);
    }
    public record OrganizationPage(List<IncidentReorganization.Receipt> items,UUID nextCursor){}
    private void require(Principal p, ResourceRef ref, Permission permission) { if (authorization.authorize(p, ref, permission).denied()) throw new IncidentFailure(FORBIDDEN); }
    public record Page(List<IncidentRecord.Header> items, UUID nextCursor) {}
}
