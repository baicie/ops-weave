package com.acme.opsweave.incident.infrastructure;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.api.IncidentStore;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

/** Explicit development memory store. Stage the complete page before making it visible. */
public final class InMemoryIncidentStore implements IncidentStore, com.acme.opsweave.incident.api.ProblemHistoryReader {
    private final Map<Key,IncidentRecord> records = new HashMap<>();
    private final Map<RequestKey,SavedTransition> requests = new HashMap<>();
    private final Map<Occurrence,UUID> owners = new HashMap<>();
    private final Map<Key,IncidentReorganization.Receipt> organizations = new HashMap<>();
    private final Map<Key,com.acme.opsweave.alerting.domain.ProblemObservation> history = new HashMap<>();
    private final HostResolver resolver;
    public InMemoryIncidentStore(HostResolver resolver) { this.resolver = resolver; }
    public synchronized ImportResult ingest(TenantId tenant, String source, String mode, List<ExternalProblem> problems, Instant receivedAt) {
        IncidentStore.validatePage(tenant, source, mode, problems, receivedAt);
        var hostIds = new HashSet<String>(); problems.forEach(p -> hostIds.addAll(p.hostIds()));
        var mapped = resolver.resolve(tenant, source, hostIds); var staged = new HashMap<Key,IncidentRecord>();
        var created = new HashSet<UUID>(); var changed = new HashSet<UUID>(); var stagedOwners = new HashMap<Occurrence,UUID>(); int unmapped = 0;
        var captured = new HashMap<Key,com.acme.opsweave.alerting.domain.ProblemObservation>();
        for (var problem : problems) {
            var occurrence = new Occurrence(tenant, source, problem.problemEventId());
            var key = new Key(tenant, owners.getOrDefault(occurrence, IncidentRecord.initialId(problem)));
            var links = new HashMap<String,EntityId>(); for (String host : problem.hostIds()) if (mapped.containsKey(host)) links.put(host, mapped.get(host));
            var applied = IncidentProjection.observe(staged.getOrDefault(key, records.get(key)), problem, mode, links, receivedAt);
            var observation = com.acme.opsweave.alerting.domain.ProblemObservation.capture(problem,mode,receivedAt,links);
            var observationKey = new Key(tenant,observation.id()); var old = history.get(observationKey);
            if (old != null && !old.sameInput(observation)) throw new IncidentFailure(CONFLICT);
            if (old == null) captured.put(observationKey,observation);
            staged.put(key, applied.record()); stagedOwners.put(occurrence, key.id()); if (applied.created()) created.add(key.id()); else if (applied.changed()) changed.add(key.id());
            var storedProblem = applied.record().problems().stream().filter(p -> p.observation().problemEventId().equals(problem.problemEventId())
                && p.observation().sourceInstanceId().equals(source)).findFirst().orElseThrow();
            unmapped += (int) storedProblem.observation().hostIds().stream().filter(host -> !storedProblem.entities().containsKey(host)).count();
        }
        changed.removeAll(created); records.putAll(staged); owners.putAll(stagedOwners); history.putAll(captured); return new ImportResult(problems.size(), created.size(), changed.size(), unmapped);
    }
    public synchronized Optional<IncidentRecord> find(TenantId tenant, UUID id, IncidentVisibility visibility) {
        return Optional.ofNullable(records.get(new Key(tenant, id))).filter(visibility::includes);
    }
    public synchronized List<IncidentRecord.Header> page(TenantId tenant, IncidentVisibility visibility, IncidentStatus status, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid incident limit");
        return records.values().stream().filter(r -> !r.merged() && r.incident().tenantId().equals(tenant) && visibility.includes(r))
            .map(IncidentRecord::incident).filter(h -> (status == null || status == h.status()) && (after == null || h.id().toString().compareTo(after.toString()) > 0))
            .sorted(Comparator.comparing(h -> h.id().toString())).limit(limit + 1L).toList();
    }
    public synchronized TransitionResult transition(TenantId tenant, UUID id, IncidentVisibility visibility, long expected, IncidentStatus target, UUID key, String actor, Instant now) {
        var record = find(tenant, id, visibility).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
        var request = new RequestKey(tenant, id, key); var saved = requests.get(request);
        if (saved != null) {
            if (saved.expected() != expected || saved.target() != target || !saved.actor().equals(actor)) throw new IncidentFailure(CONFLICT);
            return saved.result();
        }
        var updated = IncidentProjection.transition(record, expected, target, key, actor, now);
        var result = new TransitionResult(id, target, updated.incident().version());
        records.put(new Key(tenant, id), updated); requests.put(request, new SavedTransition(expected, target, actor, result)); return result;
    }
    private record Key(TenantId tenant, UUID id) {}
    public synchronized List<com.acme.opsweave.alerting.domain.ProblemObservation> observations(TenantId tenant,UUID incidentId,IncidentVisibility visibility,ProblemHistoryQuery query) {
        var current=find(tenant,incidentId,visibility).orElseThrow(()->new IncidentFailure(NOT_FOUND));
        if(current.incident().version()!=query.incidentVersion())throw new IncidentFailure(CONFLICT);
        return history.values().stream().filter(entry->entry.observation().tenantId().equals(tenant))
            .filter(entry->incidentId.equals(owners.get(new Occurrence(tenant,entry.observation().sourceInstanceId(),entry.observation().problemEventId()))))
            .filter(entry->query.includes(entry)&&entry.visibleTo(visibility.allEntities(),visibility.entities()))
            .sorted(Comparator.comparing(entry->entry.id().toString())).limit(query.limit()+1L).toList();
    }
    private record Occurrence(TenantId tenant, String source, String event) {}
    public synchronized IncidentReorganization.Receipt reorganize(TenantId tenant, IncidentReorganization.Request request, IncidentVisibility visibility, String actor, Instant now) {
        var source = find(tenant, request.sourceIncidentId(), visibility).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
        var key = new Key(tenant, request.requestKey()); var saved = organizations.get(key);
        if (saved != null) {
            if (!saved.request().equals(request) || !saved.actor().equals(actor)) throw new IncidentFailure(CONFLICT);
            return reorganization(tenant, request.requestKey(), visibility).orElseThrow(() -> new IncidentFailure(NOT_FOUND));
        }
        var target = records.get(new Key(tenant, request.targetIncidentId()));
        if (target != null && !visibility.includes(target)) throw new IncidentFailure(NOT_FOUND);
        var applied = IncidentReorganization.apply(source, target, request, actor, now);
        if (!visibility.includes(applied.target()) || !visibility.includes(applied.source())) throw new IncidentFailure(FORBIDDEN);
        for (var problem : applied.receipt().movedProblems()) {
            if (!request.sourceIncidentId().equals(owners.get(new Occurrence(tenant, problem.sourceInstanceId(), problem.problemEventId())))) throw new IncidentFailure(CONFLICT);
        }
        records.put(new Key(tenant, request.sourceIncidentId()), applied.source()); records.put(new Key(tenant, request.targetIncidentId()), applied.target());
        for (var problem : applied.receipt().movedProblems()) owners.put(new Occurrence(tenant, problem.sourceInstanceId(), problem.problemEventId()), request.targetIncidentId());
        organizations.put(key, applied.receipt()); return applied.receipt();
    }
    public synchronized Optional<IncidentReorganization.Receipt> reorganization(TenantId tenant, UUID key, IncidentVisibility visibility) {
        var saved = organizations.get(new Key(tenant, key));
        if (saved == null || find(tenant, saved.request().sourceIncidentId(), visibility).isEmpty() || find(tenant, saved.request().targetIncidentId(), visibility).isEmpty()) return Optional.empty();
        return Optional.of(saved);
    }
    public synchronized List<IncidentReorganization.Receipt> reorganizations(TenantId tenant, UUID incidentId, IncidentVisibility visibility, UUID after, int limit) {
        if (limit<1||limit>25) throw new IncidentFailure(INVALID_REQUEST);
        return organizations.entrySet().stream().filter(e->e.getKey().tenant().equals(tenant)) .map(Map.Entry::getValue)
            .filter(r->r.request().sourceIncidentId().equals(incidentId)||r.request().targetIncidentId().equals(incidentId))
            .filter(r->after==null||r.request().requestKey().toString().compareTo(after.toString())>0)
            .filter(r->find(tenant,r.request().sourceIncidentId(),visibility).isPresent()&&find(tenant,r.request().targetIncidentId(),visibility).isPresent())
            .sorted(Comparator.comparing(r->r.request().requestKey().toString())).limit(limit+1L).toList();
    }
    private record RequestKey(TenantId tenant, UUID id, UUID request) {}
    private record SavedTransition(long expected, IncidentStatus target, String actor, TransitionResult result) {}
}
