package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.SourceInstance.*;
import java.time.*;
import java.util.*;

/** Explicit CAS edits over an immutable creation receipt. No network calls, secrets, or implicit task updates. */
public final class SourceInstanceService {
    public record Edit(UUID requestId, int expectedEditVersion, String name, String description, String connectionDigest, String state) {
        public Edit { Objects.requireNonNull(requestId); WorkflowDefinition.checkDigest(connectionDigest);
            if(expectedEditVersion<1 || expectedEditVersion>1000 || name==null || name.isBlank() || name.length()>80 || !name.equals(name.trim())
                || description==null || description.length()>500 || !Set.of("ACTIVE","ARCHIVED").contains(state))throw new IllegalArgumentException("Invalid source edit"); }
        public String digest(UUID id) { return WorkflowDefinition.hash(List.of("source-instance-edit-v2",id.toString(),requestId.toString(),Integer.toString(expectedEditVersion),name,description,connectionDigest,state)); }
    }
    public record Page(List<SourceInstance> items, boolean truncated) {}
    private final WorkflowStore store;
    private final SourceSetupService.Connections connections;
    private final Clock clock;
    public SourceInstanceService(WorkflowStore store,SourceSetupService.Connections connections,Clock clock){this.store=store;this.connections=connections;this.clock=clock;}
    public void authorize(Principal p) {
        if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
    }
    SourceInstance current(WorkflowStore.Session s,Principal p,UUID id) {
        var setup=s.setup(p.subjectId().value(),id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
        if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),setup.source().instanceId()),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var maintained=s.sourceInstance(p.subjectId().value(),id);
        var instance=maintained.orElseGet(()->SourceInstance.initial(setup));
        if(!instance.id().equals(id) || !instance.source().equals(setup.source()) || !instance.createdAt().equals(setup.createdAt()))throw new IllegalStateException("Source instance unavailable");
        var configurations=s.sourceConfigurations(p.subjectId().value(),id);
        if(maintained.isPresent()){
            if(configurations.size()!=instance.configurationRevision() || configurations.stream().map(Configuration::revision).distinct().count()!=configurations.size()
                || configurations.stream().anyMatch(c->!c.sourceId().equals(id)||c.revision()>instance.configurationRevision()||c.createdAt().isBefore(instance.createdAt())||c.createdAt().isAfter(instance.updatedAt())||instance.source().kind().equals("MANUAL_SAMPLE")!=c.dataMode().equals("MANUAL_SAMPLE"))
                || configurations.stream().noneMatch(c->c.revision()==instance.configurationRevision()&&c.connectionDigest().equals(instance.connectionDigest())&&c.dataMode().equals(instance.dataMode())))throw new IllegalStateException("Source configuration unavailable");
        }else if(!configurations.isEmpty())throw new IllegalStateException("Source configuration unavailable");
        SourceConnectionConfiguration.requireLineage(instance,configurations,s.sourceConnections(p.subjectId().value(),id));return instance;
    }
    public SourceInstance read(Principal p,UUID id){authorize(p);return store.transaction(p.tenantId(),s->current(s,p,id));}
    public Page list(Principal p) {
        authorize(p);return store.transaction(p.tenantId(),s->{
            var items=new ArrayList<SourceInstance>();
            for(var setup:s.setups(p.subjectId().value())) {
                if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),setup.source().instanceId()),Permission.SOURCE_SYNC).allowed())items.add(current(s,p,setup.id()));
            }
            items.sort(Comparator.comparing(SourceInstance::updatedAt).reversed().thenComparing(i->i.id().toString()));
            return new Page(List.copyOf(items.subList(0,Math.min(20,items.size()))),items.size()>20);
        });
    }
    public List<Configuration> configurations(Principal p,UUID id) {
        authorize(p);return store.transaction(p.tenantId(),s->{var instance=current(s,p,id);var rows=s.sourceConfigurations(p.subjectId().value(),id);
            if(rows.isEmpty())return List.of(instance.configuration());
            if(rows.stream().noneMatch(c->c.revision()==instance.configurationRevision() && c.connectionDigest().equals(instance.connectionDigest()) && c.dataMode().equals(instance.dataMode())))throw new IllegalStateException("Source configuration unavailable");
            return rows;
        });
    }
    public CommandReceipt receipt(Principal p,UUID id,UUID requestId) {
        authorize(p);return store.transaction(p.tenantId(),s->{current(s,p,id);var receipt=s.sourceCommand(p.subjectId().value(),requestId).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
            if(!receipt.sourceId().equals(id))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);return receipt;});
    }
    public CommandReceipt edit(Principal p,UUID id,Edit command) {
        authorize(p);return store.transaction(p.tenantId(),s->{
            String owner=p.subjectId().value();var previous=current(s,p,id);String digest=command.digest(id);
            var known=s.sourceCommand(owner,command.requestId());
            if(known.isPresent()){if(!known.get().commandDigest().equals(digest))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);return known.get();}
            if(previous.editVersion()!=command.expectedEditVersion())throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            if(previous.editVersion()>=1000 || s.sourceCommandCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
            boolean changed=!previous.connectionDigest().equals(command.connectionDigest());
            if(!s.sourceConnections(owner,id).isEmpty()){
                if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),previous.source().instanceId()),Permission.SOURCE_CONFIGURE).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
                if(changed)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            }
            if(previous.state().equals("ARCHIVED") && (!previous.name().equals(command.name()) || !previous.description().equals(command.description()) || changed))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            if(changed && (!previous.state().equals("ACTIVE") || !command.state().equals("ACTIVE")))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            String mode=previous.dataMode();int revision=previous.configurationRevision();
            if(changed){
                if(revision>=100)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
                var connection=connections.resolve(p,previous.source());
                if(!connection.digest().equals(command.connectionDigest()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                mode=connection.dataMode();revision++;
            }
            if(command.state().equals("ARCHIVED") && s.task(owner,previous.workflowId()).map(t->t.state().equals("RUNNING")).orElse(false))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(now.isBefore(previous.updatedAt()))throw new IllegalStateException("Source clock unavailable");
            var next=new SourceInstance(id,command.name(),command.description(),previous.source(),revision,command.connectionDigest(),mode,previous.editVersion()+1,command.state(),previous.createdAt(),now);
            if(s.sourceConfigurations(owner,id).isEmpty())s.addSourceConfiguration(owner,previous.configuration());
            if(changed)s.addSourceConfiguration(owner,next.configuration());
            var receipt=new CommandReceipt(command.requestId(),id,digest,next);
            s.saveSourceInstance(owner,next);s.addSourceCommand(owner,receipt);return receipt;
        });
    }
}
