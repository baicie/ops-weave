package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import java.time.*;
import java.util.*;

public final class ModelSpendService {
    private final AuthorizationService authorization; private final IncidentService incidents; private final ToolReadStore sessions;
    private final ModelSpendStore store; private final ModelSpend.Policy policy; private final Clock clock;
    public ModelSpendService(AuthorizationService authorization,IncidentService incidents,ToolReadStore sessions,ModelSpendStore store,ModelSpend.Policy policy,Clock clock) {
        this.authorization=authorization;this.incidents=incidents;this.sessions=sessions;this.store=store;this.policy=policy;this.clock=clock;
    }
    private void authorize(Principal p,UUID run,UUID incident) {
        for(var permission:Set.of(Permission.AI_DIAGNOSE,Permission.AI_INSIGHT_READ,Permission.INCIDENT_READ,Permission.ENTITY_READ,Permission.METRIC_READ,Permission.EVIDENCE_READ))
            if(!p.has(permission)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        if(authorization.authorize(p,ResourceRef.incident(p.tenantId(),incident),Permission.AI_DIAGNOSE).denied()
            || authorization.authorize(p,new ResourceRef(p.tenantId(),"ai-insight",run.toString()),Permission.AI_INSIGHT_READ).denied()) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        incidents.get(p,incident);
    }
    public ModelSpend.Call reserve(Principal p,UUID run,UUID sessionId,String digest,int bytes) {
        var session=sessions.session(p.tenantId(),p.subjectId(),sessionId).orElseThrow(()->new ToolFailure(ToolFailure.Code.NOT_FOUND));
        authorize(p,run,session.incidentId()); var now=clock.instant();
        if(!now.isBefore(session.deadlineAt())) throw new ToolFailure(ToolFailure.Code.EXPIRED);
        if(session.usedCalls()!=2) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        return store.reserve(new ModelSpend.Call(p.tenantId(),p.subjectId(),run,sessionId,session.incidentId(),digest,bytes,policy,now,session.deadlineAt(),null,null));
    }
    public ModelSpend.Call get(Principal p,UUID run) {
        var call=store.find(p.tenantId(),run).orElseThrow(()->new ToolFailure(ToolFailure.Code.NOT_FOUND));
        if(!call.subjectId().equals(p.subjectId())) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        authorize(p,run,call.incidentId()); return call;
    }
    public ModelSpend.Call report(Principal p,UUID run,UUID session,ModelSpend.Usage usage) {
        get(p,run); return store.report(p.tenantId(),p.subjectId(),run,session,usage,clock.instant());
    }
    public void requireReport(Principal p,InsightSubmission submission) {
        // Historical mock submissions remain compatible. New current Runtime always reserves and reports mock no-call usage.
        var found=store.find(p.tenantId(),submission.runId());
        if(found.isEmpty() && submission.model().provider().equals("mock-deterministic")) return;
        var call=get(p,submission.runId());
        if(call.usage()==null || !call.sessionId().equals(submission.sessionId()) || !call.policy().provider().equals(submission.model().provider())
            || !call.policy().model().equals(submission.model().name())) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);
    }
}
