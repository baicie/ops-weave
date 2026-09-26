package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;

/** Reauthorize retained scope before revealing that an object once existed. Never return scrubbed content. */
final class RetiredReadAccess {
    private RetiredReadAccess() {}
    static void reject(Principal p,AiRetention.Marker m,AuthorizationService auth,IncidentService incidents) {
        var permission=m.kind()==AiRetention.Kind.INSIGHT?Permission.AI_INSIGHT_READ:Permission.EVIDENCE_READ;
        var type=m.kind()==AiRetention.Kind.INSIGHT?"ai-insight":"evidence";
        if(!m.tenantId().equals(p.tenantId()) || auth.authorize(p,new ResourceRef(m.tenantId(),type,m.id().toString()),permission).denied())throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        incidents.get(p,m.incidentId());
        for(var id:m.entityIds())if(auth.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_READ).denied())throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        for(var key:m.metricKeys())if(auth.authorize(p,ResourceRef.metric(p.tenantId(),key),Permission.METRIC_READ).denied())throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        throw new ToolFailure(ToolFailure.Code.EXPIRED);
    }
}
