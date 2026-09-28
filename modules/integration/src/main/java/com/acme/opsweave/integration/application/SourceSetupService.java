package com.acme.opsweave.integration.application;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import java.time.*;
import java.util.*;

/** Configuration confirmation creates a private setup and first workflow draft in ONE transaction. */
public final class SourceSetupService {
    public record Connection(String digest,String dataMode) {}
    public interface Connections { Connection resolve(Principal principal, Source source); }
    public record Command(UUID requestId,String name,String description,Source source,String connectionDigest,Target target) {
        public Command { String mode=source.kind().equals("MANUAL_SAMPLE")?"MANUAL_SAMPLE":"fixture";new SourceSetup(requestId,name,description,source,connectionDigest,mode,target,SourceSetup.fingerprint(requestId,name,description,source,connectionDigest,mode,target),Instant.EPOCH); }
        String digest(String mode) { return SourceSetup.fingerprint(requestId,name,description,source,connectionDigest,mode,target); }
    }
    public record Confirmed(SourceSetup setup,Entry workflow) {}
    public record Page(List<SourceSetup> items,boolean truncated) {}
    private final WorkflowStore store;
    private final WorkflowService.Models models;
    private final Connections connections;
    private final Clock clock;
    public SourceSetupService(WorkflowStore store,WorkflowService.Models models,Connections connections,Clock clock) {this.store=store;this.models=models;this.connections=connections;this.clock=clock;}
    public void authorize(Principal p) {if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);}
    public Confirmed confirm(Principal p,Command command) {
        authorize(p);
        var connection=connections.resolve(p,command.source());
        if(!connection.digest().equals(command.connectionDigest()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        ModelDefinition model=models.resolve(p,command.target());
        var definition=seed(command,model);definition.requireModel(model);
        return store.transaction(p.tenantId(),s->{
            String owner=p.subjectId().value();var existing=s.setup(owner,command.requestId());
            if(existing.isPresent()) {if(!existing.get().digest().equals(command.digest(connection.dataMode())))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);return new Confirmed(existing.get(),first(s,owner,existing.get()));}
            if(s.setups(owner).size()>=200 || s.drafts(owner).size()>=50)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
            if(s.draft(owner,definition.id(),1).isPresent()||s.published(definition.id(),1).isPresent())throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var setup=new SourceSetup(command.requestId(),command.name(),command.description(),command.source(),command.connectionDigest(),connection.dataMode(),command.target(),command.digest(connection.dataMode()),now);
            var positions=new LinkedHashMap<String,Position>();for(int i=0;i<definition.nodes().size();i++)positions.put(definition.nodes().get(i).id(),new Position(180,40+i*116));
            var draft=new Entry(definition,definition.digest(),"DRAFT",1,positions,now,null);
            s.saveSetup(owner,setup);s.saveDraft(owner,draft);
            return new Confirmed(setup,draft);
        });
    }
    public Page list(Principal p) {authorize(p);return store.transaction(p.tenantId(),s->{var entries=s.setups(p.subjectId().value());return new Page(List.copyOf(entries.subList(0,Math.min(20,entries.size()))),entries.size()>20);});}
    public Confirmed read(Principal p,UUID id) {
        authorize(p);return store.transaction(p.tenantId(),s->{String owner=p.subjectId().value();var setup=s.setup(owner,id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));return new Confirmed(setup,first(s,owner,setup));});
    }
    private static Entry first(WorkflowStore.Session s,String owner,SourceSetup setup) {
        // This receipt refers to the initial workflow. Further revisions are explicitly created in the editor.
        return s.published(setup.workflowId(),1).orElseGet(()->s.draft(owner,setup.workflowId(),1).orElseThrow(()->new IllegalStateException("Source setup draft unavailable")));
    }
    private static WorkflowDefinition seed(Command c,ModelDefinition model) {
        if(model.fields().isEmpty())throw new IllegalArgumentException("An entity model with fields is required");
        var map=new LinkedHashMap<String,String>();
        for(var f:model.fields()) {if(c.source().kind().equals("MANUAL_SAMPLE"))map.put(f.id(),f.id());else {if(f.id().equals("hostname")||f.id().equals("name"))map.put("name",f.id());if(f.id().equals("ip"))map.put("ip","ip");}}
        if(map.isEmpty())map.put("name",model.fields().getFirst().id());
        var nodes=List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",map),new Node("trim",Type.TRIM,"1",Map.of()),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of()));
        var edges=new ArrayList<Edge>();for(int i=0;i<nodes.size()-1;i++)edges.add(new Edge(nodes.get(i).id(),nodes.get(i+1).id()));
        return new WorkflowDefinition("source-"+c.requestId(),1,c.name(),c.source(),c.target(),nodes,edges);
    }
}
