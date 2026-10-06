package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.SourceInspectionService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import java.util.*;

/** Existing registered Host connector only. Discovery stores types and missingness, never values. */
public final class HostSourceInspectionReader implements SourceInspectionService.Reader {
    private final Connector connector;private final OpsweaveProperties properties;private final ManagedSourceConnections connections;
    public HostSourceInspectionReader(Connector connector,OpsweaveProperties properties){this.connector=connector;this.properties=properties;connections=new ManagedSourceConnections(properties);}
    public SourceInspectionService.Result read(Principal p,WorkflowDefinition.Source source,String kind){
        var profile=connections.profile(p,source);boolean fixture=profile.get("dataMode").equals("fixture");
        var context=new Connector.SourceContext(p.tenantId(),source.instanceId(),properties.zabbix().secretRef());
        return inspect(connector,context,fixture,kind);
    }
    static SourceInspectionService.Result inspect(Connector connector,Connector.SourceContext context,boolean fixture,String kind) {
        if(!Set.of("TEST","DISCOVER","DISCOVER_METRICS").contains(kind))throw new IllegalArgumentException("Invalid source inspection kind");
        if(kind.equals("DISCOVER_METRICS")){
            if(!fixture)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
            var mappings=com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(HostSourceInspectionReader.class.getClassLoader());
            return new SourceInspectionService.Result(null,null,new com.acme.opsweave.integration.infrastructure.ZabbixMetricMetadataReader(mappings).fixture(context));
        }
        if(kind.equals("TEST")){
            var probe=connector.probe(context);if(!probe.reachable())return new SourceInspectionService.Result(new SourceInspection.Check(false,"UNREACHABLE",null),null);
            // Version discovery alone is anonymous; require an authenticated bounded read as well.
            var page=connector.fetch(context,null,1);
            if(page.records().size()>1)throw new IllegalStateException("Source exceeded check budget");
            boolean verified=fixture||SyncScan.HOSTID_WATERMARK.equals(page.scanConsistency())&&(page.snapshotComplete()||page.nextCursor()!=null);
            return new SourceInspectionService.Result(new SourceInspection.Check(true,fixture?"LABELED_FIXTURE":verified?"READ_VERIFIED":"UNVERIFIED",probe.reportedVersion()),null);
        }
        var page=connector.fetch(context,null,5);if(page.records().size()>5)throw new IllegalStateException("Source exceeded discovery budget");
        String method=fixture?"LABELED_FIXTURE":SyncScan.HOSTID_WATERMARK.equals(page.scanConsistency())?"HOSTID_WATERMARK":"UNVERIFIED";
        boolean complete=page.snapshotComplete()&&page.nextCursor()==null&&!method.equals("UNVERIFIED");
        var fields=new ArrayList<SourceInspection.Field>();
        if(!page.records().isEmpty())for(String key:List.of("hostid","host","name","status","interfaces.ip")){
            var types=new HashSet<String>();boolean nullable=false;
            for(var record:page.records()){
                Object value=record.payload().get(key);
                if(key.equals("interfaces.ip")) {var raw=record.payload().get("interfaces");if(raw instanceof List<?> list){var ips=new ArrayList<Object>();for(var item:list)if(item instanceof Map<?,?> map&&map.get("ip")!=null)ips.add(map.get("ip"));value=ips.isEmpty()?null:ips;}else value=null;}
                if(value==null){nullable=true;continue;}
                types.add(value instanceof String?"TEXT":value instanceof Number?"NUMBER":value instanceof Boolean?"BOOLEAN":key.equals("interfaces.ip")&&value instanceof List<?> ips&&ips.stream().allMatch(String.class::isInstance)?"TEXT_ARRAY":"MIXED");
            }
            fields.add(new SourceInspection.Field(key,types.isEmpty()?"NULL":types.size()==1?types.iterator().next():"MIXED",nullable));
        }
        return new SourceInspectionService.Result(null,new SourceInspection.Discovery(fields,page.records().size(),complete,method,complete?"READ_VERIFIED":"INCOMPLETE",SourceInspection.fieldDigest(fields)));
    }
}
