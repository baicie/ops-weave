package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.SourceInspection;
import com.acme.opsweave.integration.domain.SourceMetricDiscovery;
import com.acme.opsweave.integration.domain.SourceMetricPage;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class SourceInspectionJson {
    private SourceInspectionJson() {}
    public static Map<String,Object> wire(SourceInspection r) {
        var n=new LinkedHashMap<String,Object>();n.put("requestId",r.requestId());n.put("sourceId",r.sourceId());n.put("kind",r.kind());n.put("configurationRevision",r.configurationRevision());
        n.put("connectionDigest",r.connectionDigest());n.put("dataMode",r.dataMode());n.put("commandDigest",r.commandDigest());n.put("state",r.state());n.put("asOf",r.asOf().toString());n.put("deadline",r.deadline().toString());
        n.put("availableAt",r.availableAt()==null?null:r.availableAt().toString());n.put("expiresAt",r.expiresAt()==null?null:r.expiresAt().toString());n.put("check",r.check());
        n.put("discovery",r.discovery()==null?null:Map.of("fields",r.discovery().fields(),"observedRecords",r.discovery().observedRecords(),"complete",r.discovery().complete(),"scanConsistency",r.discovery().scanConsistency(),"statusCode",r.discovery().statusCode(),"fingerprint",r.discovery().fingerprint(),"scope",r.discovery().scope()));
        n.put("metricDiscovery",r.metricDiscovery()==null?null:Map.of("items",r.metricDiscovery().items(),"complete",r.metricDiscovery().complete(),"scanConsistency",r.metricDiscovery().scanConsistency(),"statusCode",r.metricDiscovery().statusCode(),"fingerprint",r.metricDiscovery().fingerprint(),"scope",r.metricDiscovery().scope(),"limit",r.metricDiscovery().limit()));n.put("previousRequestId",r.previousRequestId());n.put("metricPage",r.metricPage()==null?null:page(r.metricPage()));return n;
    }
    public static Map<String,Object> storage(SourceInspection r){
        var n=wire(r);n.put("metricMembership",r.metricPage()==null||r.metricPage().manifest()==null?null:r.metricPage().manifest().itemIds());return n;
    }
    private static Map<String,Object> page(SourceMetricPage p){
        var n=new LinkedHashMap<String,Object>();var m=p.manifest();
        n.put("manifest",m==null?null:Map.of("snapshotId",m.snapshotId(),"asOf",m.asOf().toString(),"expiresAt",m.expiresAt().toString(),"total",m.total(),"fingerprint",m.fingerprint()));
        n.put("offset",p.offset());n.put("items",p.items());n.put("statusCode",p.statusCode());n.put("scanConsistency",p.scanConsistency());
        n.put("fingerprint",p.fingerprint());n.put("complete",p.complete());n.put("nextOffset",p.nextOffset());n.put("limit",SourceMetricPage.LIMIT);return n;
    }
    public static SourceInspection decode(String json) {
        var n=CatalogJson.JSON.readTree(json);var allowed=new HashSet<>(Set.of("requestId","sourceId","kind","configurationRevision","connectionDigest","dataMode","commandDigest","state","asOf","deadline","availableAt","expiresAt","check","discovery"));for(var key:List.of("metricDiscovery","previousRequestId","metricPage","metricMembership"))if(n.has(key))allowed.add(key);fields(n,allowed);
        SourceInspection.Check check=null;SourceInspection.Discovery discovery=null;SourceMetricDiscovery metrics=null;
        if(!n.path("check").isNull()){var c=n.get("check");fields(c,Set.of("reachable","statusCode","reportedVersion"));check=new SourceInspection.Check(bool(c,"reachable"),text(c,"statusCode"),c.path("reportedVersion").isNull()?null:text(c,"reportedVersion"));}
        if(!n.path("discovery").isNull()){
            var d=n.get("discovery");fields(d,Set.of("fields","observedRecords","complete","scanConsistency","statusCode","fingerprint","scope"));if(!text(d,"scope").equals("FIRST_HOST_PAGE")||!d.path("fields").isArray())throw new IllegalArgumentException();
            var f=new ArrayList<SourceInspection.Field>();for(var v:d.get("fields")){fields(v,Set.of("name","type","nullable"));f.add(new SourceInspection.Field(text(v,"name"),text(v,"type"),bool(v,"nullable")));}
            discovery=new SourceInspection.Discovery(f,integer(d,"observedRecords",null),bool(d,"complete"),text(d,"scanConsistency"),text(d,"statusCode"),text(d,"fingerprint"));
        }
        if(n.has("metricDiscovery")&&!n.get("metricDiscovery").isNull()){
            var d=n.get("metricDiscovery");fields(d,Set.of("items","complete","scanConsistency","statusCode","fingerprint","scope","limit"));
            if(!text(d,"scope").equals("FIRST_ITEM_PAGE")||integer(d,"limit",null)!=20||!d.path("items").isArray()||d.get("items").size()>20)throw new IllegalArgumentException();
            var items=items(d.get("items"));
            metrics=new SourceMetricDiscovery(items,bool(d,"complete"),text(d,"scanConsistency"),text(d,"statusCode"),text(d,"fingerprint"));
        }
        SourceMetricPage page=null;UUID previous=null;
        if(n.has("previousRequestId")&&!n.get("previousRequestId").isNull())previous=UUID.fromString(text(n,"previousRequestId"));
        if(n.has("metricPage")&&!n.get("metricPage").isNull()){
            var d=n.get("metricPage");fields(d,Set.of("manifest","offset","items","statusCode","scanConsistency","fingerprint","complete","nextOffset","limit"));
            if(integer(d,"limit",null)!=20)throw new IllegalArgumentException();SourceMetricPage.Manifest m=null;
            if(!d.path("manifest").isNull()){
                var a=d.get("manifest");fields(a,Set.of("snapshotId","asOf","expiresAt","total","fingerprint"));
                if(!n.path("metricMembership").isArray()||n.get("metricMembership").size()>1000)throw new IllegalArgumentException();
                var ids=new ArrayList<String>();for(var value:n.get("metricMembership")){if(!value.isString())throw new IllegalArgumentException();ids.add(value.asString());}
                m=new SourceMetricPage.Manifest(UUID.fromString(text(a,"snapshotId")),Instant.parse(text(a,"asOf")),Instant.parse(text(a,"expiresAt")),ids,text(a,"fingerprint"));
                if(m.total()!=integer(a,"total",null))throw new IllegalArgumentException();
            }else if(n.has("metricMembership")&&!n.get("metricMembership").isNull())throw new IllegalArgumentException();
            page=new SourceMetricPage(m,integer(d,"offset",null),items(d.get("items")),text(d,"statusCode"),text(d,"scanConsistency"),text(d,"fingerprint"));
            if(page.complete()!=bool(d,"complete")||!d.has("nextOffset")||!Objects.equals(page.nextOffset(),d.get("nextOffset").isNull()?null:integer(d,"nextOffset",null)))throw new IllegalArgumentException();
        }else if(n.has("metricMembership")&&!n.get("metricMembership").isNull())throw new IllegalArgumentException();
        return new SourceInspection(UUID.fromString(text(n,"requestId")),UUID.fromString(text(n,"sourceId")),text(n,"kind"),integer(n,"configurationRevision",null),text(n,"connectionDigest"),text(n,"dataMode"),text(n,"commandDigest"),text(n,"state"),Instant.parse(text(n,"asOf")),Instant.parse(text(n,"deadline")),instant(n,"availableAt"),instant(n,"expiresAt"),check,discovery,metrics,previous,page);
    }
    private static List<SourceMetricDiscovery.Item> items(JsonNode values){
        if(values==null||!values.isArray()||values.size()>20)throw new IllegalArgumentException();
            var items=new ArrayList<SourceMetricDiscovery.Item>();for(var v:values){
                fields(v,Set.of("itemId","hostId","sourceKey","name","sourceUnit","sourceValueType","mappingStatus","mapping"));SourceMetricDiscovery.Mapping m=null;
                if(!v.get("mapping").isNull()){var a=v.get("mapping");fields(a,Set.of("id","revision","digest","metricKey","unit","valueType","valueTransform"));m=new SourceMetricDiscovery.Mapping(text(a,"id"),integer(a,"revision",null),text(a,"digest"),text(a,"metricKey"),text(a,"unit"),text(a,"valueType"),text(a,"valueTransform"));}
                items.add(new SourceMetricDiscovery.Item(text(v,"itemId"),text(v,"hostId"),text(v,"sourceKey"),text(v,"name"),nullableEmptyText(v,"sourceUnit"),text(v,"sourceValueType"),text(v,"mappingStatus"),m));
            }
        return items;
    }
    private static String nullableEmptyText(JsonNode n,String key){if(!n.path(key).isString())throw new IllegalArgumentException();return n.get(key).asString();}
    private static Instant instant(JsonNode n,String key){if(!n.has(key))throw new IllegalArgumentException();return n.get(key).isNull()?null:Instant.parse(text(n,key));}
    private static boolean bool(JsonNode n,String key){if(!n.path(key).isBoolean())throw new IllegalArgumentException();return n.get(key).booleanValue();}
}
