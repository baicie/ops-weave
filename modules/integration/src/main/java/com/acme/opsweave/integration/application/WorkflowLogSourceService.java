package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;

/** Selects existing server-owned LOG metadata without reading bodies or creating new connections. */
public final class WorkflowLogSourceService {
    public record Selection(WorkflowDefinition.Source source,SourceMetricDiscovery.Item item,Instant asOf,Instant expiresAt){}
    public record Page(List<Selection> items,boolean truncated){public Page{items=List.copyOf(items);}}
    private final WorkflowStore store;private final SourceConnectionService connections;private final Clock clock;
    public WorkflowLogSourceService(WorkflowStore store,SourceConnectionService connections,Clock clock){this.store=store;this.connections=connections;this.clock=clock;}
    public static boolean allowed(Principal p,String instance,String host,String item){
        var entity=com.acme.opsweave.inventory.domain.EntityIds.fromExternal(new com.acme.opsweave.inventory.domain.ExternalObjectKey(p.tenantId(),instance,"host",host,ZabbixHostMapper.GENERATION));
        var authorizer=new Authorizer();
        return authorizer.decide(p,ResourceRef.source(p.tenantId(),instance),Permission.SOURCE_SYNC).allowed()
            &&authorizer.decide(p,ResourceRef.entity(p.tenantId(),entity),Permission.ENTITY_READ).allowed()
            &&authorizer.decide(p,new ResourceRef(p.tenantId(),"log","source."+instance+".item."+item),Permission.LOG_READ).allowed();
    }
    public void requireTarget(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source,WorkflowDefinition.Target target,boolean available){
        if(!source.kind().equals("ZABBIX_LOG"))return;
        if(!target.kind().equals("LOG"))throw failure();require(s,p,source,available);
    }
    public SourceMetricDiscovery.Item require(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source,boolean available){
        var configuration=connections.workflowConfiguration(s,p,source,available);var pin=Objects.requireNonNull(source.log());var c=source.configuration();
        var r=s.sourceInspection(p.subjectId().value(),pin.inspectionId()).orElseThrow(WorkflowLogSourceService::failure);
        if(!r.sourceId().equals(c.sourceId())||r.configurationRevision()!=c.revision()||!r.connectionDigest().equals(c.digest())
            ||!r.state().equals("COMPLETED")||!r.dataMode().equals("zabbix-jsonrpc")||r.availableAt().isAfter(clock.instant())||r.asOf().isBefore(configuration.createdAt()))throw failure();
        root(s,p,r);var item=items(r).stream().filter(i->i.itemId().equals(pin.itemId())).findFirst().orElseThrow(WorkflowLogSourceService::failure);
        if(!item.sourceValueType().equals("LOG")||!WorkflowLogSourcePin.from(r.requestId(),item).equals(pin))throw failure();
        if(!allowed(p,source.instanceId(),item.hostId(),item.itemId()))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        return item;
    }
    public Page list(Principal p,WorkflowDefinition.Source configuredHost){
        if(!configuredHost.kind().equals("ZABBIX_HOST")||configuredHost.configuration()==null)throw new IllegalArgumentException();
        return store.transaction(p.tenantId(),s->{
            var configuration=connections.workflowConfiguration(s,p,configuredHost,true);var c=configuredHost.configuration();var selected=new LinkedHashMap<String,Selection>();
            var receipts=s.sourceInspections(p.subjectId().value(),c.sourceId());
            for(var r:receipts){
                if(!r.state().equals("COMPLETED")||!r.dataMode().equals("zabbix-jsonrpc")||r.configurationRevision()!=c.revision()||!r.connectionDigest().equals(c.digest())
                    ||r.availableAt().isAfter(clock.instant())||r.asOf().isBefore(configuration.createdAt())||!clock.instant().isBefore(r.expiresAt()))continue;
                if(r.metricPage()!=null&&r.metricPage().manifest()!=null&&!clock.instant().isBefore(r.metricPage().manifest().expiresAt()))continue;
                root(s,p,r);
                for(var item:items(r)){
                    if(!item.sourceValueType().equals("LOG")||!allowed(p,configuredHost.instanceId(),item.hostId(),item.itemId()))continue;
                    var pin=WorkflowLogSourcePin.from(r.requestId(),item);
                    selected.putIfAbsent(item.itemId(),new Selection(new WorkflowDefinition.Source("ZABBIX_LOG",configuredHost.instanceId(),c,null,pin),item,r.asOf(),r.metricPage()!=null?r.metricPage().manifest().expiresAt():r.expiresAt()));
                }
            }
            var rows=new ArrayList<>(selected.values());return new Page(rows.subList(0,Math.min(100,rows.size())),rows.size()>100||receipts.size()==20);
        });
    }
    private static List<SourceMetricDiscovery.Item> items(SourceInspection r){
        if(r.kind().equals("DISCOVER_METRICS")&&r.metricDiscovery()!=null&&r.metricDiscovery().complete()&&r.metricDiscovery().scanConsistency().equals("FIRST_PAGE_MATCH"))return r.metricDiscovery().items();
        if(r.kind().equals("DISCOVER_METRIC_PAGE")&&r.metricPage()!=null&&r.metricPage().verified()&&r.metricPage().scanConsistency().equals("ITEMID_WATERMARK"))return r.metricPage().items();return List.of();
    }
    private static void root(WorkflowStore.Session s,Principal p,SourceInspection r){
        if(r.metricPage()==null||r.metricPage().manifest()==null)return;var m=r.metricPage().manifest();var root=s.sourceInspection(p.subjectId().value(),m.snapshotId()).orElseThrow(WorkflowLogSourceService::failure);
        if(!root.sourceId().equals(r.sourceId())||root.previousRequestId()!=null||!root.state().equals("COMPLETED")||root.metricPage()==null||!m.equals(root.metricPage().manifest())||root.configurationRevision()!=r.configurationRevision()||!root.connectionDigest().equals(r.connectionDigest()))throw failure();
    }
    private static WorkflowFailure failure(){return new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
}
