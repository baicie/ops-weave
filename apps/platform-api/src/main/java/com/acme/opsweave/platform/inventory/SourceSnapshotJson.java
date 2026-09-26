package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class SourceSnapshotJson {
    private SourceSnapshotJson() {}
    public static Map<String,Object> input(SourceSnapshot.Input i){return Map.of("requestId",i.requestId().toString(),"observedAt",i.observedAt().toString(),"complete",i.complete(),"records",i.records().stream().map(r->Map.of("externalId",r.externalId(),"assetUuid",r.assetUuid(),"values",r.values())).toList());}
    public static SourceSnapshot.Input input(JsonNode n){
        PipelineJson.fields(n,Set.of("requestId","observedAt","complete","records"));
        if(n.get("complete")==null || !n.get("complete").isBoolean() || n.get("records")==null || !n.get("records").isArray() || n.get("records").size()>SourceSnapshot.MAX_RECORDS)throw new IllegalArgumentException("Invalid snapshot");
        var records=new ArrayList<SourceSnapshot.Row>();for(var r:n.get("records")){PipelineJson.fields(r,Set.of("externalId","assetUuid","values"));records.add(new SourceSnapshot.Row(PipelineJson.text(r,"externalId"),PipelineJson.text(r,"assetUuid"),SourceReviewJson.strings(r.get("values"))));}
        return new SourceSnapshot.Input(SourceReviewJson.uuid(PipelineJson.text(n,"requestId")),SourceReviewJson.instant(PipelineJson.text(n,"observedAt")),n.get("complete").asBoolean(),records);
    }
    public static Map<String,Object> wire(SourceSnapshot.Receipt r){var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("storage","postgres");m.put("dataMode","import");m.put("tenantId",r.tenantId().value());m.put("actor",r.actor());m.put("sourceInstanceId",r.sourceInstanceId());m.put("namespace",r.namespace());m.put("input",input(r.input()));m.put("ingestedAt",r.ingestedAt().toString());m.put("mappingDigest",r.mappingDigest());m.put("markedAbsent",r.markedAbsent());
        m.put("engine",SourceSnapshot.ENGINE);m.put("resolved",r.resolved().stream().map(v->Map.of("externalId",v.externalId(),"entityId",v.entityId().value().toString(),"identity",AssetIdentityJson.pin(v.identity()),"reviewId",v.reviewId().toString(),"entityVersion",v.entityVersion())).toList());return m;}
    public static SourceSnapshot.Receipt receipt(String text){var n=SourceReviewJson.JSON.readTree(text);var resolved=new ArrayList<SourceSnapshot.Resolved>();for(var r:n.get("resolved"))resolved.add(new SourceSnapshot.Resolved(PipelineJson.text(r,"externalId"),EntityId.parse(PipelineJson.text(r,"entityId")),AssetIdentityJson.pin(r.get("identity")),SourceReviewJson.uuid(PipelineJson.text(r,"reviewId")),SourceReviewJson.positiveLong(r,"entityVersion")));
        var r=new SourceSnapshot.Receipt(new TenantId(PipelineJson.text(n,"tenantId")),PipelineJson.text(n,"actor"),PipelineJson.text(n,"sourceInstanceId"),PipelineJson.text(n,"namespace"),input(n.get("input")),SourceReviewJson.instant(PipelineJson.text(n,"ingestedAt")),PipelineJson.text(n,"mappingDigest"),resolved,PipelineJson.integer(n,"markedAbsent",null));
        if(!SourceReviewJson.JSON.readTree(SourceReviewJson.JSON.writeValueAsString(wire(r))).equals(n))throw new IllegalStateException("Invalid stored snapshot");return r;}
    public static Map<String,Object> wire(SourceSnapshot.Presence p){var m=new LinkedHashMap<String,Object>();m.put("tenantId",p.tenantId().value());m.put("entityId",p.entityId().value().toString());m.put("sourceInstanceId",p.sourceInstanceId());m.put("externalId",p.externalId());m.put("identity",AssetIdentityJson.pin(p.identity()));m.put("observedAt",p.observedAt().toString());m.put("ingestedAt",p.ingestedAt().toString());m.put("expiresAt",p.expiresAt().toString());m.put("present",p.present());m.put("snapshotId",p.snapshotId().toString());return m;}
    public static SourceSnapshot.Presence presence(String text){var n=SourceReviewJson.JSON.readTree(text);var p=new SourceSnapshot.Presence(new TenantId(PipelineJson.text(n,"tenantId")),EntityId.parse(PipelineJson.text(n,"entityId")),PipelineJson.text(n,"sourceInstanceId"),PipelineJson.text(n,"externalId"),AssetIdentityJson.pin(n.get("identity")),SourceReviewJson.instant(PipelineJson.text(n,"observedAt")),SourceReviewJson.instant(PipelineJson.text(n,"ingestedAt")),n.get("present").asBoolean(),SourceReviewJson.uuid(PipelineJson.text(n,"snapshotId")));if(!SourceReviewJson.JSON.readTree(SourceReviewJson.JSON.writeValueAsString(wire(p))).equals(n))throw new IllegalStateException("Invalid stored presence");return p;}
}
