package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.AssetIdentity;
import com.acme.opsweave.inventory.api.AssetIdentityStore;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AssetIdentityJson {
    private AssetIdentityJson() {}
    public static Map<String,Object> wire(AssetIdentity r) {
        var m=new LinkedHashMap<String,Object>(); m.put("schemaVersion","1.0"); m.put("id",r.id().toString()); m.put("tenantId",r.tenantId().value()); m.put("entityId",r.entityId().value().toString());
        m.put("namespace",r.namespace()); m.put("kind","asset-uuid"); m.put("value",r.value()); m.put("version",r.version()); m.put("status",r.active()?"ACTIVE":"REVOKED"); m.put("verification","operator-confirmed");
        m.put("actor",r.actor()); m.put("reason",r.reason()); m.put("assertedAt",r.assertedAt().toString());
        var v=r.revocation(); m.put("revocation",v == null ? null : Map.of("requestId",v.requestId().toString(),"actor",v.actor(),"reason",v.reason(),"at",v.at().toString())); return m;
    }
    public static Map<String,Object> pin(AssetIdentity.Pin p) { return Map.of("id",p.id().toString(),"namespace",p.namespace(),"value",p.value(),"version",p.version()); }
    public static AssetIdentity.Pin pin(JsonNode n) {
        if(n == null) return null; PipelineJson.fields(n,Set.of("id","namespace","value","version"));
        return new AssetIdentity.Pin(SourceReviewJson.uuid(PipelineJson.text(n,"id")),PipelineJson.text(n,"namespace"),PipelineJson.text(n,"value"),PipelineJson.integer(n,"version",null));
    }
    public static String encode(AssetIdentity r) { return SourceReviewJson.JSON.writeValueAsString(wire(r)); }
    public static AssetIdentity decode(String text) {
        var n=SourceReviewJson.JSON.readTree(text); var v=n.get("revocation");
        var revoke=v.isNull()?null:new AssetIdentity.Revocation(SourceReviewJson.uuid(PipelineJson.text(v,"requestId")),PipelineJson.text(v,"actor"),PipelineJson.text(v,"reason"),SourceReviewJson.instant(PipelineJson.text(v,"at")));
        var r=new AssetIdentity(SourceReviewJson.uuid(PipelineJson.text(n,"id")),new TenantId(PipelineJson.text(n,"tenantId")),EntityId.parse(PipelineJson.text(n,"entityId")),PipelineJson.text(n,"namespace"),PipelineJson.text(n,"value"),
            PipelineJson.text(n,"actor"),PipelineJson.text(n,"reason"),SourceReviewJson.instant(PipelineJson.text(n,"assertedAt")),revoke);
        if(!SourceReviewJson.JSON.readTree(encode(r)).equals(n)) throw new IllegalStateException("Invalid persisted asset identity"); return r;
    }
    public static String encode(AssetIdentity.Command c) {
        var m=new LinkedHashMap<String,Object>(); m.put("requestId",c.requestId().toString()); m.put("action",c.action().name()); m.put("identityId",c.identityId().toString()); m.put("expectedEntityVersion",c.expectedEntityVersion());
        m.put("value",c.value()); m.put("actor",c.actor()); m.put("reason",c.reason()); return SourceReviewJson.JSON.writeValueAsString(m);
    }
    public static AssetIdentity.Command command(String text) {
        var n=SourceReviewJson.JSON.readTree(text); var r=new AssetIdentity.Command(SourceReviewJson.uuid(PipelineJson.text(n,"requestId")),AssetIdentity.Action.valueOf(PipelineJson.text(n,"action")),SourceReviewJson.uuid(PipelineJson.text(n,"identityId")),
            SourceReviewJson.positiveLong(n,"expectedEntityVersion"),n.get("value").isNull()?null:PipelineJson.text(n,"value"),PipelineJson.text(n,"actor"),PipelineJson.text(n,"reason"));
        if(!SourceReviewJson.JSON.readTree(encode(r)).equals(n)) throw new IllegalStateException("Invalid persisted identity command"); return r;
    }
    public static Map<String,Object> receipt(AssetIdentityStore.Receipt r) { return Map.of("schemaVersion","1.0","requestId",r.command().requestId().toString(),"action",r.command().action().name(),"identity",wire(r.identity()),"entityVersion",r.entityVersion()); }
}
