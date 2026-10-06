package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowSampleRecovery.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Metadata only: no source, output, evaluator or authorization issuer port. */
public final class WorkflowSampleRecoveryService {
    private final WorkflowStore store;private final Clock clock;
    public WorkflowSampleRecoveryService(WorkflowStore store,Clock clock){this.store=store;this.clock=clock;}
    private record Proof(WorkflowQuality.Reference reference,String state,String batchDigest,Instant updatedAt,int records) {}
    private static WorkflowFailure fail(WorkflowFailure.Code code){return new WorkflowFailure(code);}
    private static void permission(Principal p,ResourceRef object,Permission permission){if(new Authorizer().decide(p,object,permission).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private static void ref(WorkflowQuality.Reference expected,String id,int revision,String digest){if(!expected.matches(id,revision,digest))throw fail(WorkflowFailure.Code.CONFLICT);}
    private static WorkflowDefinition version(Principal p,WorkflowStore.Session s,WorkflowQuality.Reference reference){
        permission(p,new ResourceRef(p.tenantId(),"workflow",reference.id()),Permission.SOURCE_SYNC);
        var e=s.published(reference.id(),reference.revision()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));
        if(!e.digest().equals(reference.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
        var d=e.definition();if(d.source().configuration()!=null)permission(p,ResourceRef.source(p.tenantId(),d.source().instanceId()),Permission.SOURCE_SYNC);return d;
    }
    private static Proof proof(Principal p,WorkflowStore.Session s,Kind kind,UUID id){
        if(!p.has(Permission.SOURCE_SYNC))throw fail(WorkflowFailure.Code.FORBIDDEN);var owner=p.subjectId().value();
        if(kind==Kind.METRIC_SAMPLE){
            var b=s.metricOutput(owner,id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));var r=new WorkflowQuality.Reference(b.workflowId(),b.revision(),b.digest());var d=version(p,s,r);
            if(!"ZABBIX_METRIC".equals(d.source().kind())||d.source().metric()==null||d.source().configuration()==null||d.target().mappingPin()==null||!"METRIC".equals(d.target().kind()))throw fail(WorkflowFailure.Code.CONFLICT);
            permission(p,ResourceRef.metric(p.tenantId(),d.target().metricKey()),Permission.METRIC_READ);
            var labels=b.labels();if(!p.tenantId().value().equals(labels.get("tenant_id"))||!WorkflowDefinition.hash(List.of(owner)).substring(7).equals(labels.get("owner_scope"))
                ||!d.source().instanceId().equals(labels.get("source_instance_id"))||!d.target().metricKey().equals(labels.get("metric_key"))
                ||!d.source().metric().itemId().equals(labels.get("external_item_id"))||!d.source().metric().hostId().equals(labels.get("host_external_id"))
                ||!d.source().configuration().digest().equals(labels.get("configuration_digest"))||!d.target().mappingPin().digest().equals(labels.get("mapping_digest"))
                ||!d.target().mappingPin().id().equals(labels.get("mapping_id"))||!Integer.toString(d.target().mappingPin().revision()).equals(labels.get("mapping_revision")))throw fail(WorkflowFailure.Code.CONFLICT);
            return new Proof(r,b.state(),b.batchDigest(),b.updatedAt(),b.timestamps().size());
        }
        var b=s.logOutput(owner,id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));var r=new WorkflowQuality.Reference(b.workflowId(),b.revision(),b.digest());var d=version(p,s,r);
        if(!"LOG".equals(d.target().kind())||!Set.of("MANUAL_SAMPLE","ZABBIX_LOG").contains(d.source().kind()))throw fail(WorkflowFailure.Code.CONFLICT);
        permission(p,new ResourceRef(p.tenantId(),"log","workflow."+d.id()),Permission.LOG_READ);permission(p,new ResourceRef(p.tenantId(),"log","workflow."+d.id()),Permission.LOG_WRITE);
        return new Proof(r,b.state(),b.batchDigest(),b.updatedAt(),b.accepted());
    }
    private static void parent(Proof b,Receipt r){ref(r.reference(),b.reference().id(),b.reference().revision(),b.reference().digest());if(!b.state().equals("UNKNOWN")||!b.batchDigest().equals(r.batchDigest())||!b.updatedAt().equals(r.proofUpdatedAt())||b.records()!=r.uncertainRecords())throw new IllegalStateException("Invalid sample closure parent");}
    public Receipt receipt(Principal p,UUID id){if(!p.has(Permission.SOURCE_SYNC))throw fail(WorkflowFailure.Code.FORBIDDEN);return store.transaction(p.tenantId(),s->{var r=s.sampleRecovery(p.subjectId().value(),id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));parent(proof(p,s,r.kind(),r.batchId()),r);return r;});}
    public Status status(Principal p,Kind kind,UUID id){return store.transaction(p.tenantId(),s->{var b=proof(p,s,kind,id);var r=s.sampleRecoveryForBatch(p.subjectId().value(),kind,id).orElse(null);if(r!=null)parent(b,r);return new Status("2.0",r);});}
    public Receipt abandon(Principal p,Command c){
        permission(p,new ResourceRef(p.tenantId(),"workflow",c.id()),Permission.SOURCE_SYNC);
        return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var b=proof(p,s,c.kind(),c.batchId());ref(new WorkflowQuality.Reference(c.id(),c.revision(),c.digest()),b.reference().id(),b.reference().revision(),b.reference().digest());
            var prior=s.sampleRecovery(owner,c.requestId());if(prior.isPresent()){prior.get().require(c);parent(b,prior.get());return prior.get();}
            if(s.sampleRecoveryForBatch(owner,c.kind(),c.batchId()).isPresent())throw fail(WorkflowFailure.Code.CONFLICT);
            if(!b.state().equals("UNKNOWN")||!b.batchDigest().equals(c.batchDigest())||!b.updatedAt().equals(c.expectedUpdatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);
            if(s.sampleRecoveryCount(owner)>=200)throw fail(WorkflowFailure.Code.CAPACITY);var time=clock.instant().truncatedTo(ChronoUnit.MICROS);if(time.isBefore(b.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);
            var r=new Receipt("2.0",c.requestId(),c.commandDigest(),b.reference(),c.kind(),c.batchId(),b.batchDigest(),b.updatedAt(),time,"ABANDONED",b.records());r.require(c);s.addSampleRecovery(owner,r);return r;
        });
    }
}
