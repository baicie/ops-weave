package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

public final class AiInsightService {
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final ToolGateway gateway;
    private final ToolReadStore tools;
    private final AiInsightStore store;
    private final InsightEncoding encoding;
    private final Clock clock;
    public AiInsightService(AuthorizationService authorization, IncidentService incidents, ToolGateway gateway, ToolReadStore tools,
            AiInsightStore store, InsightEncoding encoding, Clock clock) {
        this.authorization = authorization; this.incidents = incidents; this.gateway = gateway; this.tools = tools; this.store = store; this.encoding = encoding; this.clock = clock;
    }
    public AiInsight submit(Principal p, InsightSubmission input) {
        if (authorization.authorize(p, new ResourceRef(p.tenantId(), "ai-insight", input.runId().toString()), Permission.AI_INSIGHT_READ).denied()) throw new ToolFailure(FORBIDDEN);
        String digest = encoding.digest(input);
        var existing = store.find(p.tenantId(), input.runId());
        if (existing.isPresent()) {
            var value = existing.get();
            if (!value.subjectId().equals(p.subjectId()) || !value.requestDigest().equals(digest)) throw new ToolFailure(INPUT_CHANGED);
            authorize(p, value.incidentId(), Permission.AI_DIAGNOSE);
            return readable(p, value);
        }
        var retired=store.retired(p.tenantId(),input.runId());
        if(retired.isPresent()) {
            if(!retired.get().subjectId().equals(p.subjectId()) || !retired.get().requestDigest().equals(digest))throw new ToolFailure(INPUT_CHANGED);
            RetiredReadAccess.reject(p,retired.get(),authorization,incidents);
        }
        var session = tools.session(p.tenantId(), p.subjectId(), input.sessionId()).orElseThrow(() -> new ToolFailure(NOT_FOUND));
        authorize(p, session.incidentId(), Permission.AI_DIAGNOSE);
        Instant now = clock.instant();
        if (!now.isBefore(session.deadlineAt())) throw new ToolFailure(EXPIRED);
        if (session.usedCalls() != 4 || !tools.rechecked(p.tenantId(), p.subjectId(), session.id(), Set.copyOf(input.evidenceIds()))) throw new ToolFailure(INVALID_REQUEST);
        if (input.asOf().isBefore(session.createdAt()) || input.completedAt().isAfter(now) || !input.completedAt().isBefore(session.deadlineAt())) throw new ToolFailure(INVALID_REQUEST);
        var current = incidents.get(p, session.incidentId());
        if (current.incident().version() != session.incidentVersion() || !current.entityIds().equals(session.entityIds())) throw new ToolFailure(INPUT_CHANGED);
        var evidence = input.evidenceIds().stream().map(id -> gateway.getEvidence(p, id)).toList();
        if (!evidence.get(0).kind().equals("incident") || !evidence.get(1).kind().equals("metric")) throw new ToolFailure(INVALID_REQUEST);
        for (var e : evidence) {
            if (!e.sessionId().equals(session.id()) || !e.incidentId().equals(session.incidentId()) || e.incidentVersion() != session.incidentVersion()
                || !e.entityIds().equals(session.entityIds()) || !e.queryWindow().equals(session.window()) || e.availableAt().isAfter(input.asOf())) throw new ToolFailure(INVALID_REQUEST);
        }
        var warnings = new TreeSet<String>(); var modes = new TreeSet<String>(); evidence.forEach(e -> { warnings.addAll(e.warnings()); modes.addAll(e.dataModes()); });
        var normalized = input.withInsight(input.insight().protect(List.copyOf(warnings), input.model().provider().equals("mock-deterministic")));
        var result = new AiInsight(p.tenantId(), p.subjectId(), session.incidentId(), session.incidentVersion(), session.entityIds(), session.window(),
            normalized, digest, clock.instant(), evidence.stream().map(PlatformEvidence::expiresAt).min(Comparator.naturalOrder()).orElseThrow(), List.copyOf(modes), List.copyOf(warnings));
        if (encoding.bytes(result) > 98304) throw new ToolFailure(READ_LIMIT);
        return store.save(result);
    }
    public AiInsight get(Principal p, UUID id) {
        var value=store.find(p.tenantId(),id);if(value.isPresent())return readable(p,value.get());
        store.retired(p.tenantId(),id).ifPresent(m->RetiredReadAccess.reject(p,m,authorization,incidents));throw new ToolFailure(NOT_FOUND);
    }
    private AiInsight readable(Principal p, AiInsight value) {
        if (authorization.authorize(p, new ResourceRef(p.tenantId(), "ai-insight", value.input().runId().toString()), Permission.AI_INSIGHT_READ).denied()) throw new ToolFailure(FORBIDDEN);
        incidents.get(p, value.incidentId()); value.checkLive(clock.instant());
        for (UUID id : value.input().evidenceIds()) gateway.getEvidence(p, id);
        return value;
    }
    private void authorize(Principal p, UUID incidentId, Permission permission) {
        if (authorization.authorize(p, ResourceRef.incident(p.tenantId(), incidentId), permission).denied()) throw new ToolFailure(FORBIDDEN);
    }
}
