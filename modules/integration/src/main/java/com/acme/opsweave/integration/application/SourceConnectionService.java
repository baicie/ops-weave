package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;

/** Atomic instance/configuration/receipt writes; no secret submission, network IO or implicit task changes. */
public final class SourceConnectionService {
    public record Write(UUID requestId,int expectedEditVersion,String name,String description,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds){
        public Write(UUID requestId,int expectedEditVersion,String name,String description,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin){this(requestId,expectedEditVersion,name,description,endpointPin,credentialPin,List.of());}
        public Write {Objects.requireNonNull(requestId);Objects.requireNonNull(endpointPin);Objects.requireNonNull(credentialPin);hostGroupIds=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);SourceCredential.checkName(name);if(expectedEditVersion<0||expectedEditVersion>1000||description==null||description.length()>500)throw new IllegalArgumentException("Invalid source connection command");}
        public String digest(UUID id){
            var values=new ArrayList<String>(List.of(hostGroupIds.isEmpty()?"source-connection-write-v2":"source-connection-write-v3",id.toString(),requestId.toString(),Integer.toString(expectedEditVersion),name,description,endpointPin.id(),endpointPin.digest(),credentialPin.credentialId().toString(),Integer.toString(credentialPin.revision()),credentialPin.versionId().toString()));
            if(!hostGroupIds.isEmpty()){values.add(Integer.toString(hostGroupIds.size()));values.addAll(hostGroupIds);}return WorkflowDefinition.hash(values);
        }
    }
    public record Receipt(SourceInstance.CommandReceipt receipt,SourceConnectionConfiguration connection) {}
    public record View(SourceInstance instance,SourceConnectionConfiguration connection,String availability,boolean canConfigure) {}
    private final WorkflowStore store;private final SourceEndpointService endpoints;private final SourceCredentialService credentials;private final SourceInstanceService instances;private final Clock clock;
    public SourceConnectionService(WorkflowStore store,SourceEndpointService endpoints,SourceCredentialService credentials,Clock clock){this.store=store;this.endpoints=endpoints;this.credentials=credentials;this.clock=clock;instances=new SourceInstanceService(store,(p,s)->{throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);},clock);}
    private boolean allowed(Principal p,String type,String id,Permission permission){return new Authorizer().decide(p,new ResourceRef(p.tenantId(),type,id),permission).allowed();}
    private void require(Principal p,String type,String id,Permission permission){if(!allowed(p,type,id,permission))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);}
    private void publicPin(Principal p,SourceConnectionConfiguration c){if(!(allowed(p,"source-endpoint",c.endpoint().id(),Permission.SOURCE_SYNC)||allowed(p,"source-endpoint",c.endpoint().id(),Permission.SOURCE_CONFIGURE))||!(allowed(p,"credential",c.credentialPin().credentialId().toString(),Permission.SOURCE_SYNC)||allowed(p,"credential",c.credentialPin().credentialId().toString(),Permission.SOURCE_CONFIGURE)))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);}
    private SourceConnectionConfiguration fixed(WorkflowStore.Session s,Principal p,SourceInstance i,int revision){var c=s.sourceConnections(p.subjectId().value(),i.id()).stream().filter(row->row.revision()==revision).findFirst().orElse(null);if(c!=null)publicPin(p,c);return c;}
    public Optional<SourceConnectionConfiguration> configuration(Principal p,UUID id,int revision){instances.authorize(p);if(revision<1||revision>100)throw new IllegalArgumentException();return store.transaction(p.tenantId(),s->{var i=instances.current(s,p,id);if(revision>i.configurationRevision())throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);return Optional.ofNullable(fixed(s,p,i,revision));});}
    public List<SourceConnectionConfiguration> history(Principal p,UUID id){instances.authorize(p);return store.transaction(p.tenantId(),s->{instances.current(s,p,id);var rows=s.sourceConnections(p.subjectId().value(),id);rows.forEach(c->publicPin(p,c));return rows;});}
    /** Resolves an immutable workflow pin inside the caller's transaction, without network IO. */
    public SourceConnectionConfiguration workflowConfiguration(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source,boolean available){
        instances.authorize(p);var pin=Objects.requireNonNull(source.configuration());var i=instances.current(s,p,pin.sourceId());
        if(!(source.kind().equals(i.source().kind())||Set.of("ZABBIX_METRIC","ZABBIX_LOG").contains(source.kind())&&i.source().kind().equals("ZABBIX_HOST"))||!source.instanceId().equals(i.source().instanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        var c=fixed(s,p,i,pin.revision());if(c==null||!c.connectionDigest().equals(pin.digest()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(available){if(!i.state().equals("ACTIVE"))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);requireUsable(s,p,c);}
        return c;
    }
    public SourceConnectionConfiguration workflowConfiguration(Principal p,WorkflowDefinition.Source source){return store.transaction(p.tenantId(),s->workflowConfiguration(s,p,source,true));}
    private void requireUsable(WorkflowStore.Session s,Principal p,SourceConnectionConfiguration c){
        try{if(!c.scoped())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);endpoints.requireReadPin(p,c.endpoint().pin());credentials.requirePin(s,p,c.credentialPin());}
        catch(SourceCredentialFailure invalid){throw new WorkflowFailure(invalid.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
        catch(WorkflowFailure invalid){if(invalid.code()==WorkflowFailure.Code.NOT_FOUND)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);throw invalid;}
    }
    public SourceSetupService.Connection resolve(Principal p,SourceInstance i,WorkflowStore.Session s){
        var c=fixed(s,p,i,i.configurationRevision());if(c==null)return null;
        try{if(!c.scoped())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);endpoints.requireReadPin(p,c.endpoint().pin());credentials.requirePin(s,p,c.credentialPin());}
        catch(SourceCredentialFailure invalid){if(invalid.code()==SourceCredentialFailure.Code.FORBIDDEN)throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
        catch(WorkflowFailure invalid){if(invalid.code()==WorkflowFailure.Code.NOT_FOUND)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);throw invalid;}
        return new SourceSetupService.Connection(c.connectionDigest(),"zabbix-jsonrpc",true);
    }
    public View read(Principal p,UUID id){instances.authorize(p);return store.transaction(p.tenantId(),s->{var i=instances.current(s,p,id);var c=fixed(s,p,i,i.configurationRevision());String availability=c==null?"LEGACY":i.state().equals("ARCHIVED")?"ARCHIVED":"AVAILABLE";if(c!=null&&i.state().equals("ACTIVE"))try{resolve(p,i,s);}catch(WorkflowFailure unavailable){if(unavailable.code()!=WorkflowFailure.Code.SOURCE_UNAVAILABLE)throw unavailable;availability="UNAVAILABLE";}return new View(i,c,availability,allowed(p,"source",i.source().instanceId(),Permission.SOURCE_CONFIGURE));});}
    private Receipt checked(WorkflowStore.Session s,Principal p,UUID id,SourceInstance.CommandReceipt receipt){
        var latest=instances.current(s,p,id);var original=receipt.instance();var c=fixed(s,p,latest,original.configurationRevision());
        if(!receipt.sourceId().equals(id)||!original.source().equals(latest.source())||!original.createdAt().equals(latest.createdAt())||original.editVersion()>latest.editVersion()||original.updatedAt().isAfter(latest.updatedAt())||c==null||!c.connectionDigest().equals(original.connectionDigest())||c.createdAt().isAfter(original.updatedAt()))throw new IllegalStateException("Source connection receipt unavailable");
        String expected=new Write(receipt.requestId(),original.editVersion()-1,original.name(),original.description(),c.endpoint().pin(),c.credentialPin(),c.hostGroupIds()).digest(id);
        if(!receipt.commandDigest().equals(expected)){
            if(original.editVersion()>1&&receipt.commandDigest().equals(new SourceInstanceService.Edit(receipt.requestId(),original.editVersion()-1,original.name(),original.description(),original.connectionDigest(),original.state()).digest(id)))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);
            throw new IllegalStateException("Source connection receipt unavailable");
        }
        return new Receipt(receipt,c);
    }
    public Receipt receipt(Principal p,UUID id,UUID request){instances.authorize(p);return store.transaction(p.tenantId(),s->{instances.current(s,p,id);var r=s.sourceCommand(p.subjectId().value(),request).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));if(!r.sourceId().equals(id))throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);return checked(s,p,id,r);});}
    public Receipt write(Principal p,UUID id,Write command,boolean create){
        instances.authorize(p);Objects.requireNonNull(id);if(command.hostGroupIds().isEmpty()||create&&(!id.equals(command.requestId())||command.expectedEditVersion()!=0)||!create&&command.expectedEditVersion()<1)throw new IllegalArgumentException("Invalid source connection command");
        return store.transaction(p.tenantId(),s->{
            String owner=p.subjectId().value();SourceInstance previous=create?null:instances.current(s,p,id);String physical=create?SourceConnectionConfiguration.physicalId(id):previous.source().instanceId();
            require(p,"source",physical,Permission.SOURCE_SYNC);require(p,"source",physical,Permission.SOURCE_CONFIGURE);
            for(var permission:List.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE)){require(p,"source-endpoint",command.endpointPin().id(),permission);require(p,"credential",command.credentialPin().credentialId().toString(),permission);}
            String digest=command.digest(id);var known=s.sourceCommand(owner,command.requestId());
            if(known.isPresent()){if(!known.get().sourceId().equals(id)||!known.get().commandDigest().equals(digest))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);return checked(s,p,id,known.get());}
            if(s.sourceCommandCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
            if(create){if(s.sourceSetupExists(id)||s.draft(owner,"source-"+id,1).isPresent()||s.published("source-"+id,1).isPresent())throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);if(s.setups(owner).size()>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);}
            else {if(previous.source().kind().equals("MANUAL_SAMPLE")||!previous.state().equals("ACTIVE")||previous.editVersion()!=command.expectedEditVersion())throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);if(previous.editVersion()>=1000)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);}
            var endpoint=endpoints.requireReadPin(p,command.endpointPin());credentials.requirePin(s,p,command.credentialPin());
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);if(previous!=null&&now.isBefore(previous.updatedAt()))throw new IllegalStateException("Source clock unavailable");
            String connectionDigest=SourceConnectionConfiguration.fingerprint(id,command.endpointPin(),command.credentialPin(),command.hostGroupIds());boolean changed=previous==null||!previous.connectionDigest().equals(connectionDigest);
            int revision=previous==null?1:previous.configurationRevision()+(changed?1:0);if(revision>100)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
            var source=previous==null?new WorkflowDefinition.Source("ZABBIX_HOST",physical):previous.source();
            var next=new SourceInstance(id,command.name(),command.description(),source,revision,connectionDigest,"zabbix-jsonrpc",previous==null?1:previous.editVersion()+1,"ACTIVE",previous==null?now:previous.createdAt(),now);
            if(create){var setup=new SourceSetup(id,command.name(),command.description(),source,connectionDigest,"zabbix-jsonrpc",null,SourceSetup.fingerprint(id,command.name(),command.description(),source,connectionDigest,"zabbix-jsonrpc",null),now);s.saveSetup(owner,setup);}
            if(previous!=null&&s.sourceConfigurations(owner,id).isEmpty())s.addSourceConfiguration(owner,previous.configuration());
            if(changed){s.addSourceConfiguration(owner,next.configuration());s.addSourceConnection(owner,SourceConnectionConfiguration.snapshot(id,revision,endpoint,command.credentialPin(),command.hostGroupIds(),now));}
            s.saveSourceInstance(owner,next);var receipt=new SourceInstance.CommandReceipt(command.requestId(),id,digest,next);s.addSourceCommand(owner,receipt);return checked(s,p,id,receipt);
        });
    }
}
