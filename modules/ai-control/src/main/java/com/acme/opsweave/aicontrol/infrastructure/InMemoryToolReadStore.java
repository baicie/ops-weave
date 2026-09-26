package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.ToolReadStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit non-durable adapter. No automatic fallback from PostgreSQL. */
public final class InMemoryToolReadStore implements ToolReadStore {
    private final Map<UUID,ToolReadSession> sessions = new HashMap<>();
    private final Map<UUID,PlatformEvidence> evidence = new HashMap<>();
    private final Map<UUID,String> audits = new HashMap<>();
    private final Map<UUID,Set<UUID>> rechecks = new HashMap<>();
    public synchronized void create(ToolReadSession s) {
        long active = sessions.values().stream().filter(v -> v.tenantId().equals(s.tenantId()) && v.subjectId().equals(s.subjectId()) && s.createdAt().isBefore(v.deadlineAt())).count();
        if (active >= 4) throw new ToolFailure(ToolFailure.Code.BUSY);
        if (sessions.putIfAbsent(s.id(), s) != null) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);
    }
    public synchronized Call begin(TenantId tenant, SubjectId subject, UUID id, String tool, Instant now) {
        if (!ToolReadSession.TOOLS.contains(tool)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        var s = sessions.get(id);
        if (s == null || !s.tenantId().equals(tenant) || !s.subjectId().equals(subject)) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        var consumed = s.consume(now); sessions.put(id, consumed);
        var call = new Call(UUID.randomUUID(), consumed, tool, now); audits.put(call.id(), "STARTED"); return call;
    }
    public synchronized void complete(Call call, PlatformEvidence snapshot, int bytes, Instant now) {
        if (!now.isBefore(call.session().deadlineAt()) || !now.isBefore(call.startedAt().plusSeconds(15))) throw new ToolFailure(ToolFailure.Code.DEADLINE);
        if (!"STARTED".equals(audits.get(call.id())) || bytes > 32768 || bytes < 1) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
        if (snapshot != null && call.tool().equals("evidence.get@2.0.0")) {
            if (!snapshot.equals(evidence.get(snapshot.id())) || !snapshot.sessionId().equals(call.session().id())) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
            rechecks.computeIfAbsent(snapshot.sessionId(), ignored -> new HashSet<>()).add(snapshot.id());
        } else if (snapshot != null) {
            if (!snapshot.sessionId().equals(call.session().id()) || !snapshot.tenantId().equals(call.session().tenantId())
                || evidence.putIfAbsent(snapshot.id(), snapshot) != null) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
        }
        audits.put(call.id(), snapshot == null || snapshot.warnings().isEmpty() ? "OK" : "PARTIAL");
    }
    public synchronized void fail(Call call, ToolFailure.Code code, Instant now) { audits.replace(call.id(), "STARTED", code.name()); }
    public synchronized Optional<PlatformEvidence> find(TenantId tenant, UUID id) {
        return Optional.ofNullable(evidence.get(id)).filter(e -> e.tenantId().equals(tenant));
    }
    public synchronized Optional<ToolReadSession> session(TenantId tenant, SubjectId subject, UUID id) {
        return Optional.ofNullable(sessions.get(id)).filter(s -> s.tenantId().equals(tenant) && s.subjectId().equals(subject));
    }
    public synchronized boolean rechecked(TenantId tenant, SubjectId subject, UUID id, Set<UUID> ids) {
        return session(tenant, subject, id).isPresent() && rechecks.getOrDefault(id, Set.of()).containsAll(ids);
    }
    public synchronized void auditAccess(TenantId tenant, SubjectId subject, UUID id, String outcome, int bytes, Instant now) { audits.put(UUID.randomUUID(), outcome); }
    public synchronized List<String> outcomes() { return List.copyOf(audits.values()); }
}
