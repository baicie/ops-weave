package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read original validation metadata, with the same destination and scope gates as batch quality. */
public final class WorkflowDiagnosticService {
    private final WorkflowStore store;private final WorkflowService workflows;private final Clock clock;
    public WorkflowDiagnosticService(WorkflowStore store,WorkflowService workflows,Clock clock){this.store=store;this.workflows=workflows;this.clock=clock;}
    WorkflowQuality.Reference reference(Principal p,WorkflowStore.Session s,String id,int revision){workflows.authorize(p);WorkflowDefinition.ref(id,revision);var e=s.published(id,revision).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));workflows.accessibleEntry(p,s,e);if(e.definition().target().kind().equals("LOG")&&new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.LOG_READ).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);return new WorkflowQuality.Reference(id,revision,e.digest());}
    public WorkflowDiagnostics.Report report(Principal p,String id,int revision){workflows.authorize(p);return store.transaction(p.tenantId(),s->report(p,s,id,revision,clock.instant().truncatedTo(ChronoUnit.MICROS)));}
    WorkflowDiagnostics.Report report(Principal p,WorkflowStore.Session s,String id,int revision,Instant asOf){var ref=reference(p,s,id,revision);var rows=s.diagnostics(p.subjectId().value(),id,revision,ref.digest());rows.forEach(v->scope(p,s,v));return new WorkflowDiagnostics.Report("2.0",asOf,ref,rows.stream().limit(20).map(WorkflowDiagnostics.Stored::observation).toList(),rows.size()>20);}
    public WorkflowDiagnostics.Observation observation(Principal p,String id,int revision,UUID observationId){workflows.authorize(p);return store.transaction(p.tenantId(),s->{var ref=reference(p,s,id,revision);var value=s.diagnostic(p.subjectId().value(),observationId).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));if(!value.observation().reference().equals(ref))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);scope(p,s,value);return value.observation();});}
    static void scope(Principal p,WorkflowStore.Session s,WorkflowDiagnostics.Stored v){
        var o=v.observation();var source=s.published(o.reference().id(),o.reference().revision()).orElseThrow().definition().source().kind();
        var expected=switch(source){case "ZABBIX_HOST"->WorkflowQuality.Kind.HOST_SCAN;case "ZABBIX_METRIC"->WorkflowQuality.Kind.METRIC_STREAM;case "ZABBIX_LOG"->WorkflowQuality.Kind.LOG_STREAM;default->null;};
        if(o.kind()!=expected)throw new IllegalStateException("Invalid validation scope metadata");
        if(o.kind()==WorkflowQuality.Kind.HOST_SCAN&&o.relatedBatchId()!=null){
            var b=s.hostBatch(p.subjectId().value(),o.relatedBatchId()).orElseThrow(()->new IllegalStateException("Invalid validation batch reference"));
            var ids=b.records().stream().map(row->UUID.fromString((String)row.get("entity_id")).toString()).collect(java.util.stream.Collectors.toSet());
            if(!b.workflowId().equals(o.reference().id())||b.revision()!=o.reference().revision()||!b.digest().equals(o.reference().digest())||!ids.equals(new HashSet<>(v.entityIds()))||o.result().received()!=null&&b.records().size()!=o.result().received())throw new IllegalStateException("Invalid validation batch scope");
        }
        for(var id:v.entityIds())if(new Authorizer().decide(p,ResourceRef.entity(p.tenantId(),new EntityId(UUID.fromString(id))),Permission.ENTITY_READ).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
    }
}
