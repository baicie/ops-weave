package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.RegisteredItemConnector;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcProblemReader;
import com.acme.opsweave.integration.infrastructure.ZabbixMetricMetadataReader;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** Internal bounded reader. Public commands will bind these pins to a maintained instance, never a supplied URL. */
@Component
public final class RegisteredHostSourceReader {
    private final SourceEndpointReadService service;private final JacksonZabbixTransport transport;private final OpsweaveProperties properties;
    private final Semaphore capacity=new Semaphore(2);
    public RegisteredHostSourceReader(RegisteredSourceEndpoints endpoints,SourceCredentialVault vault,InventoryWiring wiring,JacksonZabbixTransport transport,OpsweaveProperties properties){
        this.transport=transport;this.properties=properties;service=new SourceEndpointReadService(new SourceEndpointService(endpoints),new SourceCredentialService(wiring.workflows(),vault,Clock.systemUTC()));
    }
    public SourceInspectionService.Result read(Principal principal,String sourceInstanceId,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,String kind){
        new WorkflowDefinition.Source("ZABBIX_HOST",sourceInstanceId);
        if(!Set.of("TEST","DISCOVER","DISCOVER_METRICS").contains(kind))throw new IllegalArgumentException("Invalid source inspection kind");
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(new Authorizer().decide(principal,ResourceRef.source(principal.tenantId(),sourceInstanceId),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());var bounded=transport.registered(uri,properties);String reference="managed:"+credentialPin.versionId();
            if(kind.equals("DISCOVER_METRICS"))return new SourceInspectionService.Result(null,null,
                new ZabbixMetricMetadataReader(ClasspathMappingCatalog.load(getClass().getClassLoader())).read(uri,bounded,new String(secret),groups));
            // The existing connector API takes a transient String. It is never cached, returned or logged.
            var connector=new ZabbixJsonRpcConnector(uri,bounded,ref->{if(!reference.equals(ref))throw new IllegalStateException("Credential reference changed");return new String(secret);},groups);
            return HostSourceInspectionReader.inspect(connector,new Connector.SourceContext(principal.tenantId(),sourceInstanceId,reference),false,kind);
        });}finally{capacity.release();}
    }
    public IngestZabbixHostsUseCase.SyncOutcome syncItems(Principal principal,String sourceInstanceId,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,com.acme.opsweave.integration.domain.SyncRun.SourceScope sourceScope,IngestZabbixItemsUseCase ingest){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        new WorkflowDefinition.Source("ZABBIX_HOST",sourceInstanceId);
        if(new Authorizer().decide(principal,ResourceRef.source(principal.tenantId(),sourceInstanceId),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());var bounded=transport.registered(uri,properties);String reference="managed:"+credentialPin.versionId();
            RegisteredItemConnector connector=new com.acme.opsweave.integration.infrastructure.ZabbixRegisteredItemConnector(uri,bounded,ref->{if(!reference.equals(ref))throw new IllegalStateException("Credential reference changed");return new String(secret);},groups);
            return ingest.executeRegistered(principal,sourceInstanceId,reference,connector,sourceScope);
        });}catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    /** Read a bounded problem page through the immutable registered connection scope. */
    public com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase.Result readProblems(
        Principal principal, String sourceInstanceId, SourceEndpoint.Pin endpointPin,
        SourceCredential.Pin credentialPin, List<String> hostGroupIds,
        com.acme.opsweave.integration.domain.ProblemReadWindow window) {
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        new WorkflowDefinition.Source("ZABBIX_HOST",sourceInstanceId);
        if(new Authorizer().decide(principal,ResourceRef.source(principal.tenantId(),sourceInstanceId),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());var bounded=transport.registered(uri,properties);String reference="managed:"+credentialPin.versionId();
            var reader=new ZabbixJsonRpcProblemReader(uri,bounded,ref->{if(!reference.equals(ref))throw new IllegalStateException("Credential reference changed");return new String(secret);},Clock.systemUTC(),groups);
            var page=reader.read(new Connector.SourceContext(principal.tenantId(),sourceInstanceId,reference),window);
            return new com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase.Result("zabbix-jsonrpc",sourceInstanceId,page);
        });}catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    public SourceMetricPage readMetricPage(Principal p,SourceInstance instance,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,SourceInspection pending,SourceMetricPage previous){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),instance.source().instanceId()),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(p,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());var bounded=transport.registered(uri,properties);
            return new com.acme.opsweave.integration.infrastructure.ZabbixMetricPageReader(ClasspathMappingCatalog.load(getClass().getClassLoader()))
                .read(uri,bounded,new String(secret),pending.requestId(),pending.asOf(),previous,groups);
        });}finally{capacity.release();}
    }
    public List<Map<String,Object>> metricWindow(Principal p,WorkflowDefinition.Source source,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,java.time.Instant from,java.time.Instant till){
        return metricWindow(p,source,endpointPin,credentialPin,hostGroupIds,from,till,com.acme.opsweave.integration.domain.WorkflowMetricStream.MAX_POINTS);
    }
    public List<Map<String,Object>> metricWindow(Principal p,WorkflowDefinition.Source source,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,java.time.Instant from,java.time.Instant till,int maxPoints){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(maxPoints<1||maxPoints>com.acme.opsweave.integration.domain.WorkflowMetricStream.MAX_POINTS)throw new IllegalArgumentException();
        if(!source.kind().equals("ZABBIX_METRIC")||source.metric()==null)throw new IllegalArgumentException();
        if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),source.instanceId()),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var entity=com.acme.opsweave.inventory.domain.EntityIds.fromExternal(new com.acme.opsweave.inventory.domain.ExternalObjectKey(p.tenantId(),source.instanceId(),"host",source.metric().hostId(),ZabbixHostMapper.GENERATION));
        if(new Authorizer().decide(p,ResourceRef.entity(p.tenantId(),entity),Permission.ENTITY_READ).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(p,endpointPin,credentialPin,(endpoint,secret)->{var uri=URI.create(endpoint.address());return new com.acme.opsweave.integration.infrastructure.ZabbixWorkflowMetricReader().readWindow(uri,transport.registered(uri,properties),new String(secret),source.metric(),from,till,java.time.Instant.now(),maxPoints,groups);});}
        catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    public WorkflowService.Batch metricPreview(Principal principal,WorkflowDefinition.Source source,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(!source.kind().equals("ZABBIX_METRIC")||source.metric()==null)throw new IllegalArgumentException();
        if(new Authorizer().decide(principal,ResourceRef.source(principal.tenantId(),source.instanceId()),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var entity=com.acme.opsweave.inventory.domain.EntityIds.fromExternal(new com.acme.opsweave.inventory.domain.ExternalObjectKey(principal.tenantId(),source.instanceId(),"host",source.metric().hostId(),ZabbixHostMapper.GENERATION));
        if(new Authorizer().decide(principal,ResourceRef.entity(principal.tenantId(),entity),Permission.ENTITY_READ).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());return new com.acme.opsweave.integration.infrastructure.ZabbixWorkflowMetricReader()
                .read(uri,transport.registered(uri,properties),new String(secret),source.metric(),java.time.Instant.now(),groups);
        });}catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    public WorkflowService.Batch logPreview(Principal principal,WorkflowDefinition.Source source,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(!source.kind().equals("ZABBIX_LOG")||source.log()==null)throw new IllegalArgumentException();
        if(!WorkflowLogSourceService.allowed(principal,source.instanceId(),source.log().hostId(),source.log().itemId()))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());return new com.acme.opsweave.integration.infrastructure.ZabbixWorkflowLogReader().read(uri,transport.registered(uri,properties),new String(secret),source.log(),java.time.Instant.now(),groups);
        });}catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    public List<Map<String,Object>> logWindow(Principal p,WorkflowDefinition.Source source,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,java.time.Instant from,java.time.Instant till){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(!source.kind().equals("ZABBIX_LOG")||source.log()==null)throw new IllegalArgumentException();
        if(!WorkflowLogSourceService.allowed(p,source.instanceId(),source.log().hostId(),source.log().itemId()))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(p,endpointPin,credentialPin,(endpoint,secret)->{var uri=URI.create(endpoint.address());return new com.acme.opsweave.integration.infrastructure.ZabbixWorkflowLogReader().readWindow(uri,transport.registered(uri,properties),new String(secret),source.log(),from,till,java.time.Instant.now(),groups);});}
        catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
    public WorkflowService.Batch preview(Principal principal,String sourceInstanceId,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds){
        var page=hostPage(principal,sourceInstanceId,endpointPin,credentialPin,hostGroupIds,null);
        if(page.records().isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        return new WorkflowService.Batch(page.records(),"zabbix-jsonrpc",page.records().size(),0,!page.complete(),"SUCCEEDED");
    }
    public WorkflowHostScan.Page hostPage(Principal principal,String sourceInstanceId,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,List<String> hostGroupIds,String cursor){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);if(groups.isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        new WorkflowDefinition.Source("ZABBIX_HOST",sourceInstanceId);
        if(new Authorizer().decide(principal,ResourceRef.source(principal.tenantId(),sourceInstanceId),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try{return service.read(principal,endpointPin,credentialPin,(endpoint,secret)->{
            var uri=URI.create(endpoint.address());var bounded=transport.registered(uri,properties);String reference="managed:"+credentialPin.versionId();
            var connector=new ZabbixJsonRpcConnector(uri,bounded,ref->{if(!reference.equals(ref))throw new IllegalStateException("Credential reference changed");return new String(secret);},groups);
            var page=connector.fetch(new Connector.SourceContext(principal.tenantId(),sourceInstanceId,reference),cursor,5);
            if(page.records().size()>5||!SyncScan.HOSTID_WATERMARK.equals(page.scanConsistency())||!page.snapshotComplete()&&page.nextCursor()==null)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
            var records=new ArrayList<Map<String,Object>>();var mapper=new ZabbixHostMapper();
            for(var raw:page.records()){
                if(mapper.rejectReason(raw.payload()).isPresent())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
                var entity=mapper.map(PipelineDefinition.zabbixHostV1(),principal.tenantId(),sourceInstanceId,raw.payload(),raw.observedAt(),java.time.Instant.now(),"preview").entity();
                if(new Authorizer().decide(principal,ResourceRef.entity(principal.tenantId(),entity.id()),Permission.ENTITY_READ).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
                records.add(Map.of("name",entity.name(),"ip",Objects.toString(entity.attributes().get("ip"),""),"lifecycle",entity.lifecycle().name(),"entity_id",entity.id().value().toString()));
            }
            return new WorkflowHostScan.Page(records,page.nextCursor(),page.snapshotComplete(),java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        });}catch(SourceCredentialFailure failed){throw new WorkflowFailure(failed.code()==SourceCredentialFailure.Code.FORBIDDEN?WorkflowFailure.Code.FORBIDDEN:WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}
    }
}
