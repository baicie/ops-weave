package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class SourceBindingCorrectionJson {
    private SourceBindingCorrectionJson() {}
    public static SourceBindingCorrection.Command command(JsonNode n){
        PipelineJson.fields(n,Set.of("requestId","externalId","expectedSnapshotId","previousEntityId","expectedPreviousVersion","targetEntityId","expectedTargetVersion","targetIdentity","observedAt","values","reason"));
        return new SourceBindingCorrection.Command(SourceReviewJson.uuid(PipelineJson.text(n,"requestId")),PipelineJson.text(n,"externalId"),SourceReviewJson.uuid(PipelineJson.text(n,"expectedSnapshotId")),
            new EntityId(SourceReviewJson.uuid(PipelineJson.text(n,"previousEntityId"))),SourceReviewJson.positiveLong(n,"expectedPreviousVersion"),new EntityId(SourceReviewJson.uuid(PipelineJson.text(n,"targetEntityId"))),SourceReviewJson.positiveLong(n,"expectedTargetVersion"),
            AssetIdentityJson.pin(n.get("targetIdentity")),SourceReviewJson.instant(PipelineJson.text(n,"observedAt")),SourceReviewJson.strings(n.get("values")),PipelineJson.text(n,"reason"));
    }
    public static Map<String,Object> command(SourceBindingCorrection.Command c){
        var m=new LinkedHashMap<String,Object>();m.put("requestId",c.requestId().toString());m.put("externalId",c.externalId());m.put("expectedSnapshotId",c.expectedSnapshotId().toString());
        m.put("previousEntityId",c.previousEntityId().value().toString());m.put("expectedPreviousVersion",c.expectedPreviousVersion());m.put("targetEntityId",c.targetEntityId().value().toString());m.put("expectedTargetVersion",c.expectedTargetVersion());
        m.put("targetIdentity",AssetIdentityJson.pin(c.targetIdentity()));m.put("observedAt",c.observedAt().toString());m.put("values",c.values());m.put("reason",c.reason());return m;
    }
    public static Map<String,Object> wire(SourceBindingCorrection.Receipt r){
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("storage","postgres");m.put("dataMode","import");m.put("tenantId",r.tenantId().value());m.put("actor",r.actor());m.put("sourceInstanceId",r.sourceInstanceId());m.put("namespace",r.namespace());
        m.put("command",command(r.command()));m.put("previous",SourceSnapshotJson.wire(r.previous()));m.put("snapshot",SourceSnapshotJson.wire(r.snapshot()));m.put("previousEntityVersionAfter",r.previousEntityVersionAfter());return m;
    }
    public static SourceBindingCorrection.Receipt receipt(String text){
        var n=SourceReviewJson.JSON.readTree(text);var r=new SourceBindingCorrection.Receipt(new TenantId(PipelineJson.text(n,"tenantId")),PipelineJson.text(n,"actor"),PipelineJson.text(n,"sourceInstanceId"),PipelineJson.text(n,"namespace"),command(n.get("command")),
            SourceSnapshotJson.presence(n.get("previous").toString()),SourceSnapshotJson.receipt(n.get("snapshot").toString()),SourceReviewJson.positiveLong(n,"previousEntityVersionAfter"));
        if(!SourceReviewJson.JSON.readTree(SourceReviewJson.JSON.writeValueAsString(wire(r))).equals(n))throw new IllegalStateException("Invalid stored correction");return r;
    }
}
