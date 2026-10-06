package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Claims before network work; replay only reads the original metadata, never repeats the probe. */
public final class SourceInspectionService {
    public record Command(UUID requestId,int configurationRevision,String connectionDigest,UUID previousRequestId) {
        public Command(UUID requestId,int configurationRevision,String connectionDigest){this(requestId,configurationRevision,connectionDigest,null);}
        public Command {Objects.requireNonNull(requestId);WorkflowDefinition.checkDigest(connectionDigest);if(configurationRevision<1||configurationRevision>100)throw new IllegalArgumentException("Invalid inspection command");}
    }
    public record Result(SourceInspection.Check check,SourceInspection.Discovery discovery,SourceMetricDiscovery metricDiscovery,SourceMetricPage metricPage) {
        public Result(SourceInspection.Check check,SourceInspection.Discovery discovery,SourceMetricDiscovery metricDiscovery){this(check,discovery,metricDiscovery,null);}
        public Result(SourceInspection.Check check,SourceInspection.Discovery discovery){this(check,discovery,null);}
    }
    public interface Reader { Result read(Principal principal,WorkflowDefinition.Source source,String kind); default Result read(Principal principal,SourceInstance instance,String kind){return read(principal,instance.source(),kind);}
        default SourceMetricPage readMetricPage(Principal p,SourceInstance i,SourceInspection pending,SourceMetricPage previous){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
    }
    public record View(SourceInspection inspection,String validity) {}
    private record Claim(SourceInspection inspection,SourceInstance instance,boolean created,SourceMetricPage previous) {}
    private final WorkflowStore store;private final SourceInstanceService instances;private final SourceSetupService.Connections connections;private final Reader reader;private final Clock clock;
    private final Semaphore capacity=new Semaphore(2);
    public SourceInspectionService(WorkflowStore store,SourceSetupService.Connections connections,Reader reader,Clock clock) {this.store=store;this.connections=connections;this.reader=reader;this.clock=clock;instances=new SourceInstanceService(store,connections,clock);}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private SourceSetupService.Connection current(WorkflowStore.Session s,Principal p,SourceInstance i){
        if(!i.state().equals("ACTIVE")||!i.source().kind().equals("ZABBIX_HOST"))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        var c=connections.resolve(p,i,s);if(!c.digest().equals(i.connectionDigest())||!c.dataMode().equals(i.dataMode()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return c;
    }
    private void validateOriginal(WorkflowStore.Session s,Principal p,SourceInstance i,SourceInspection r){
        var configurations=s.sourceConfigurations(p.subjectId().value(),i.id());
        if(configurations.isEmpty())configurations=List.of(i.configuration());
        if(!r.sourceId().equals(i.id())||configurations.stream().noneMatch(c->c.revision()==r.configurationRevision()&&c.connectionDigest().equals(r.connectionDigest())&&c.dataMode().equals(r.dataMode())&&!r.asOf().isBefore(c.createdAt())))throw new IllegalStateException("Inspection configuration unavailable");
        if(r.metricPage()!=null&&r.metricPage().manifest()!=null){
            var m=r.metricPage().manifest();
            var root=s.sourceInspection(p.subjectId().value(),m.snapshotId()).orElseThrow(()->new IllegalStateException("Metric root unavailable"));
            if(!root.sourceId().equals(r.sourceId())||!root.kind().equals("DISCOVER_METRIC_PAGE")||root.previousRequestId()!=null
                ||!root.state().equals("COMPLETED")||root.metricPage()==null||!m.equals(root.metricPage().manifest())
                ||root.configurationRevision()!=r.configurationRevision()||!root.connectionDigest().equals(r.connectionDigest())
                ||!m.asOf().equals(root.asOf()))throw new IllegalStateException("Metric root mismatch");
            if(r.previousRequestId()!=null){
                var parent=s.sourceInspection(p.subjectId().value(),r.previousRequestId()).orElseThrow(()->new IllegalStateException("Metric parent unavailable"));
                if(!parent.sourceId().equals(r.sourceId())||!parent.kind().equals("DISCOVER_METRIC_PAGE")||!parent.state().equals("COMPLETED")
                    ||parent.metricPage()==null||!m.equals(parent.metricPage().manifest())
                    ||!Objects.equals(parent.metricPage().nextOffset(),r.metricPage().offset())
                    ||parent.configurationRevision()!=r.configurationRevision()||!parent.connectionDigest().equals(r.connectionDigest())
                    ||r.asOf().isBefore(parent.availableAt()))throw new IllegalStateException("Metric parent mismatch");
            }else if(r.metricPage().offset()!=0)throw new IllegalStateException("Invalid first metric page");
        }

    }
    public View run(Principal p,UUID id,String kind,Command c) {
        instances.authorize(p);if(!Set.of("TEST","DISCOVER","DISCOVER_METRICS","DISCOVER_METRIC_PAGE").contains(kind))throw new IllegalArgumentException();
        if(!kind.equals("DISCOVER_METRIC_PAGE")&&c.previousRequestId()!=null)throw new IllegalArgumentException();
        // A process-local read budget, not a claim of distributed execution ownership.
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var claim=store.transaction(p.tenantId(),s->{
                var i=instances.current(s,p,id);String owner=p.subjectId().value();var known=s.sourceInspection(owner,c.requestId());
                String digest=SourceInspection.commandDigest(id,c.requestId(),kind,c.configurationRevision(),c.connectionDigest(),c.previousRequestId());
                if(known.isPresent()){if(!known.get().sourceId().equals(id)||!known.get().commandDigest().equals(digest))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);validateOriginal(s,p,i,known.get());return new Claim(known.get(),i,false,null);}
                var resolved=current(s,p,i);if(i.configurationRevision()!=c.configurationRevision()||!i.connectionDigest().equals(c.connectionDigest()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
                if((kind.equals("DISCOVER_METRICS")||kind.equals("DISCOVER_METRIC_PAGE"))&&i.dataMode().equals("zabbix-jsonrpc")&&!resolved.credentialPinned())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                if(s.sourceInspectionCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
                SourceMetricPage previous=null;
                if(c.previousRequestId()!=null){
                    var parent=s.sourceInspection(owner,c.previousRequestId()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
                    if(!parent.sourceId().equals(id))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);
                    validateOriginal(s,p,i,parent);
                    if(!parent.sourceId().equals(id)||!parent.kind().equals("DISCOVER_METRIC_PAGE")||!parent.state().equals("COMPLETED")
                        ||!view(s,p,i,parent).validity().equals("CURRENT")||parent.metricPage().nextOffset()==null)
                        throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
                    previous=parent.metricPage();
                }
                var pending=SourceInspection.pending(id,c.requestId(),kind,i,now(),c.previousRequestId());s.addSourceInspection(owner,pending);return new Claim(pending,i,true,previous);
            });
            if(!claim.created())return read(p,id,c.requestId());
            Result result;
            try {result=kind.equals("DISCOVER_METRIC_PAGE")?new Result(null,null,null,reader.readMetricPage(p,claim.instance(),claim.inspection(),claim.previous())):reader.read(p,claim.instance(),kind);}catch(RuntimeException unavailable){if(unavailable instanceof WorkflowFailure denied && denied.code()==WorkflowFailure.Code.FORBIDDEN||unavailable instanceof SourceCredentialFailure credentialDenied && credentialDenied.code()==SourceCredentialFailure.Code.FORBIDDEN)throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);result=switch(kind){case "DISCOVER_METRIC_PAGE"->new Result(null,null,null,SourceMetricPage.failed(claim.previous()==null?null:claim.previous().manifest(),claim.previous()==null?0:claim.previous().nextOffset(),"UNREACHABLE"));case "TEST"->new Result(new SourceInspection.Check(false,"UNREACHABLE",null),null);case "DISCOVER_METRICS"->new Result(null,null,SourceMetricDiscovery.unavailable());default->new Result(null,new SourceInspection.Discovery(List.of(),0,false,"UNVERIFIED","UNREACHABLE",SourceInspection.fieldDigest(List.of())));};}
            final Result outcome=result;
            var complete=store.transaction(p.tenantId(),s->{
                var i=instances.current(s,p,id);var original=s.sourceInspection(p.subjectId().value(),c.requestId()).orElseThrow(()->new IllegalStateException("Inspection unavailable"));
                if(!original.equals(claim.inspection()))throw new IllegalStateException("Inspection changed");
                validateOriginal(s,p,i,original);
                boolean valid=i.state().equals("ACTIVE")&&i.configurationRevision()==original.configurationRevision()&&i.connectionDigest().equals(original.connectionDigest())&&now().isBefore(original.deadline());
                if(valid){try {current(s,p,i);}catch(WorkflowFailure changed){if(changed.code()!=WorkflowFailure.Code.SOURCE_UNAVAILABLE&&changed.code()!=WorkflowFailure.Code.CONFLICT)throw changed;valid=false;}}
                if(valid&&kind.equals("DISCOVER_METRIC_PAGE"))valid=validPage(claim.inspection(),claim.previous(),outcome.metricPage());
                SourceInspection next;try {next=valid?original.complete(now(),outcome.check(),outcome.discovery(),outcome.metricDiscovery(),outcome.metricPage()):original.unknown();}catch(IllegalArgumentException invalidOutcome){next=original.unknown();}s.finishSourceInspection(p.subjectId().value(),next);return view(s,p,i,next);
            });return complete;
        } finally {capacity.release();}
    }
    private boolean validPage(SourceInspection pending,SourceMetricPage previous,SourceMetricPage page) {
        if(page==null||page.offset()!=(previous==null?0:previous.nextOffset()))return false;
        var manifest=page.manifest();
        if(previous!=null&&!previous.manifest().equals(manifest))return false;
        if(manifest!=null&&(previous==null&&(!manifest.snapshotId().equals(pending.requestId())||!manifest.asOf().equals(pending.asOf()))||!now().isBefore(manifest.expiresAt())))return false;
        return true;
    }
    public View read(Principal p,UUID id,UUID request) {instances.authorize(p);return store.transaction(p.tenantId(),s->{var i=instances.current(s,p,id);var r=s.sourceInspection(p.subjectId().value(),request).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));if(!r.sourceId().equals(id))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);validateOriginal(s,p,i,r);return view(s,p,i,r);});}
    public List<View> recent(Principal p,UUID id){instances.authorize(p);return store.transaction(p.tenantId(),s->{var i=instances.current(s,p,id);return s.sourceInspections(p.subjectId().value(),id).stream().map(r->{validateOriginal(s,p,i,r);return view(s,p,i,r);}).toList();});}
    public List<View> recent(Principal p,UUID id,String kind){
        if(!Set.of("TEST","DISCOVER","DISCOVER_METRICS","DISCOVER_METRIC_PAGE").contains(kind))throw new IllegalArgumentException();
        instances.authorize(p);return store.transaction(p.tenantId(),s->{var i=instances.current(s,p,id);return s.sourceInspections(p.subjectId().value(),id,kind).stream().map(r->{validateOriginal(s,p,i,r);return view(s,p,i,r);}).toList();});
    }
    private View view(WorkflowStore.Session s,Principal p,SourceInstance i,SourceInspection original) {
        var r=original.state().equals("PENDING")&&!now().isBefore(original.deadline())?original.unknown():original;
        if(!r.state().equals("COMPLETED"))return new View(r,"UNVERIFIED");
        if(!now().isBefore(r.expiresAt())||r.metricPage()!=null&&r.metricPage().manifest()!=null&&!now().isBefore(r.metricPage().manifest().expiresAt()))return new View(r,"EXPIRED");
        if(i.configurationRevision()!=r.configurationRevision()||!i.connectionDigest().equals(r.connectionDigest())||!i.dataMode().equals(r.dataMode())||!i.state().equals("ACTIVE"))return new View(r,"STALE");
        SourceSetupService.Connection connection;try {connection=current(s,p,i);}catch(WorkflowFailure changed){if(changed.code()==WorkflowFailure.Code.SOURCE_UNAVAILABLE||changed.code()==WorkflowFailure.Code.CONFLICT)return new View(r,"STALE");throw changed;}
        // The existing environment reference is not a versioned credential. Do not claim a reusable credential pin.
        if(r.dataMode().equals("zabbix-jsonrpc")&&!connection.credentialPinned()||r.check()!=null&&(!r.check().reachable()||r.check().statusCode().equals("UNVERIFIED"))||r.discovery()!=null&&!r.discovery().complete()||r.metricDiscovery()!=null&&!r.metricDiscovery().complete()||r.metricPage()!=null&&!r.metricPage().verified())return new View(r,"UNVERIFIED");
        return new View(r,"CURRENT");
    }
}
