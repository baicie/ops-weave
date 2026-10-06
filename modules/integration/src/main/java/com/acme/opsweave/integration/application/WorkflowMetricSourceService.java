package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;

/** Selects only server-owned discovered metadata; never reads values or creates metric bindings. */
public final class WorkflowMetricSourceService {
    public record Selection(WorkflowDefinition.Source source,SourceMetricDiscovery.Item item,Instant asOf,Instant expiresAt) {}
    public record Page(List<Selection> items,boolean truncated) { public Page { items=List.copyOf(items); } }
    private final WorkflowStore store; private final SourceConnectionService connections; private final Clock clock;
    public WorkflowMetricSourceService(WorkflowStore store,SourceConnectionService connections,Clock clock) {
        this.store=store;this.connections=connections;this.clock=clock;
    }
    public SourceMetricDiscovery.Item require(WorkflowStore.Session session,Principal p,WorkflowDefinition.Source source,boolean available) {
        var configuration=connections.workflowConfiguration(session,p,source,available);
        var pin=Objects.requireNonNull(source.metric()); var c=source.configuration();
        var r=session.sourceInspection(p.subjectId().value(),pin.inspectionId()).orElseThrow(()->failure());
        if(!r.sourceId().equals(c.sourceId()) || r.configurationRevision()!=c.revision() || !r.connectionDigest().equals(c.digest())
            || !r.state().equals("COMPLETED") || !r.dataMode().equals("zabbix-jsonrpc") || r.availableAt().isAfter(clock.instant()) || r.asOf().isBefore(configuration.createdAt())) throw failure();
        root(session,p,r);
        var item=items(r).stream().filter(i->i.itemId().equals(pin.itemId())).findFirst().orElseThrow(()->failure());
        if(!item.mappingStatus().equals("MAPPED") || !WorkflowMetricSourcePin.from(r.requestId(),item).equals(pin)) throw failure();
        if(new Authorizer().decide(p,ResourceRef.metric(p.tenantId(),item.mapping().metricKey()),Permission.METRIC_READ).denied())
            throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(!entityAllowed(p,source.instanceId(),item.hostId()))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        return item;
    }
    public void requireTarget(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source,WorkflowDefinition.Target target,boolean available) {
        if(!source.kind().equals("ZABBIX_METRIC"))return;
        var i=require(s,p,source,available);var m=i.mapping();var t=target;
        if(t.mappingPin()==null || !t.metricKey().equals(m.metricKey()) || !t.mappingPin().id().equals(m.id())
            || t.mappingPin().revision()!=m.revision() || !t.mappingPin().digest().equals(m.digest()))
            throw new WorkflowFailure(WorkflowFailure.Code.MAPPING_CHANGED);
    }
    public Page list(Principal p,WorkflowDefinition.Source configuredHost) {
        if(!configuredHost.kind().equals("ZABBIX_HOST")||configuredHost.configuration()==null)throw new IllegalArgumentException();
        return store.transaction(p.tenantId(),s->{
            connections.workflowConfiguration(s,p,configuredHost,true);var c=configuredHost.configuration();var selected=new LinkedHashMap<String,Selection>();
            var receipts=s.sourceInspections(p.subjectId().value(),c.sourceId());
            for(var r:receipts) {
                if(!r.state().equals("COMPLETED")||!r.dataMode().equals("zabbix-jsonrpc")||r.configurationRevision()!=c.revision()
                    ||!r.connectionDigest().equals(c.digest())||r.availableAt().isAfter(clock.instant())||!clock.instant().isBefore(r.expiresAt()))continue;
                if(r.metricPage()!=null&&r.metricPage().manifest()!=null&&!clock.instant().isBefore(r.metricPage().manifest().expiresAt()))continue;
                root(s,p,r);
                for(var i:items(r)) {
                    if(!i.mappingStatus().equals("MAPPED")||!Set.of("FLOAT","UNSIGNED").contains(i.sourceValueType())
                        ||new Authorizer().decide(p,ResourceRef.metric(p.tenantId(),i.mapping().metricKey()),Permission.METRIC_READ).denied()
                        ||!entityAllowed(p,configuredHost.instanceId(),i.hostId()))continue;
                    var pin=WorkflowMetricSourcePin.from(r.requestId(),i);
                    selected.putIfAbsent(i.itemId(),new Selection(new WorkflowDefinition.Source("ZABBIX_METRIC",configuredHost.instanceId(),c,pin),i,r.asOf(),
                        r.metricPage()!=null?r.metricPage().manifest().expiresAt():r.expiresAt()));
                }
            }
            var rows=new ArrayList<>(selected.values());return new Page(rows.subList(0,Math.min(100,rows.size())),rows.size()>100||receipts.size()==20);
        });
    }
    private static List<SourceMetricDiscovery.Item> items(SourceInspection r) {
        if(r.kind().equals("DISCOVER_METRICS")&&r.metricDiscovery()!=null&&r.metricDiscovery().complete()
            &&r.metricDiscovery().scanConsistency().equals("FIRST_PAGE_MATCH"))return r.metricDiscovery().items();
        if(r.kind().equals("DISCOVER_METRIC_PAGE")&&r.metricPage()!=null&&r.metricPage().verified()
            &&r.metricPage().scanConsistency().equals("ITEMID_WATERMARK"))return r.metricPage().items();
        return List.of();
    }
    private static boolean entityAllowed(Principal p,String instance,String host){var id=com.acme.opsweave.inventory.domain.EntityIds.fromExternal(new com.acme.opsweave.inventory.domain.ExternalObjectKey(p.tenantId(),instance,"host",host,ZabbixHostMapper.GENERATION));return new Authorizer().decide(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_READ).allowed();}
    private static void root(WorkflowStore.Session s,Principal p,SourceInspection r){
        if(r.metricPage()==null||r.metricPage().manifest()==null)return;var m=r.metricPage().manifest();
        var root=s.sourceInspection(p.subjectId().value(),m.snapshotId()).orElseThrow(()->failure());
        if(!root.sourceId().equals(r.sourceId())||root.previousRequestId()!=null||!root.state().equals("COMPLETED")
            ||root.metricPage()==null||!m.equals(root.metricPage().manifest())||root.configurationRevision()!=r.configurationRevision()
            ||!root.connectionDigest().equals(r.connectionDigest()))throw failure();
    }
    private static WorkflowFailure failure(){return new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
}
