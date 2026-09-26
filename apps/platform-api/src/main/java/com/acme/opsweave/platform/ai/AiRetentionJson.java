package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.acme.opsweave.platform.ai.ModelSpendJson.*;

public final class AiRetentionJson {
    private AiRetentionJson() {}
    public static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Map<String,Object> policy(AiRetention.Policy p) {
        return Map.of("tenantId",p.tenantId().value(),"version",p.version(),"insightDays",p.insightDays(),"evidenceDays",p.evidenceDays(),"auditDays",p.auditDays(),"batchSize",p.batchSize(),
            "heldIncidents",p.heldIncidents().stream().map(UUID::toString).sorted().toList(),"allowPurge",p.allowPurge());
    }
    public static AiRetention.Policy policy(JsonNode n) {
        exact(n,"tenantId","version","insightDays","evidenceDays","auditDays","batchSize","heldIncidents","allowPurge");
        if(!n.get("allowPurge").isBoolean())throw new IllegalArgumentException();
        return new AiRetention.Policy(new TenantId(text(n,"tenantId")),text(n,"version"),integer(n,"insightDays"),integer(n,"evidenceDays"),integer(n,"auditDays"),integer(n,"batchSize"),new HashSet<>(ids(n.get("heldIncidents"),1000)),n.get("allowPurge").asBoolean());
    }
    public static Map<String,Object> preview(AiRetention.Preview p) {
        return Map.of("tenantId",p.tenantId().value(),"actor",p.actor().value(),"policyDigest",p.policyDigest(),"asOf",p.asOf().toString(),"expiresAt",p.asOf().plusSeconds(120).toString(),"previewDigest",p.digest(),
            "batches",p.batches().stream().map(b->Map.of("kind",b.kind().name(),"ids",b.ids().stream().map(UUID::toString).toList(),"logicalBytes",b.logicalBytes(),"hasMore",b.hasMore())).toList());
    }
    private static AiRetention.Preview preview(JsonNode n) {
        var batches=new ArrayList<AiRetention.Batch>();
        for(var b:n.get("batches")){if(!b.get("hasMore").isBoolean())throw new IllegalArgumentException();batches.add(new AiRetention.Batch(AiRetention.Kind.valueOf(text(b,"kind")),ids(b.get("ids"),100),number(b,"logicalBytes"),b.get("hasMore").asBoolean()));}
        return new AiRetention.Preview(new TenantId(text(n,"tenantId")),new SubjectId(text(n,"actor")),text(n,"policyDigest"),Instant.parse(text(n,"asOf")),batches);
    }
    public static AiRetention.Command command(JsonNode n) {exact(n,"requestId","policyDigest","asOf","previewDigest");return new AiRetention.Command(uuid(n,"requestId"),text(n,"policyDigest"),Instant.parse(text(n,"asOf")),text(n,"previewDigest"));}
    private static Map<String,Object> command(AiRetention.Command c){return Map.of("requestId",c.requestId().toString(),"policyDigest",c.policyDigest(),"asOf",c.asOf().toString(),"previewDigest",c.previewDigest());}
    public static Map<String,Object> receipt(AiRetention.Receipt r){return Map.of("schemaVersion","1.0","storage","postgres","state","COMPLETED","tenantId",r.tenantId().value(),"actor",r.actor().value(),"command",command(r.command()),"preview",preview(r.preview()),"completedAt",r.completedAt().toString());}
    public static String encode(AiRetention.Receipt r){return JSON.writeValueAsString(receipt(r));}
    public static AiRetention.Receipt receipt(String raw){try{var n=JSON.readTree(raw);var r=new AiRetention.Receipt(new TenantId(text(n,"tenantId")),new SubjectId(text(n,"actor")),command(n.get("command")),preview(n.get("preview")),Instant.parse(text(n,"completedAt")));if(!JSON.readTree(encode(r)).equals(n))throw new IllegalArgumentException();return r;}catch(RuntimeException e){throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);}}
    public static String encode(AiRetention.Marker m){var n=new LinkedHashMap<String,Object>();n.put("schemaVersion","1.0");n.put("kind",m.kind().name());n.put("tenantId",m.tenantId().value());n.put("id",m.id().toString());n.put("sessionId",m.sessionId().toString());n.put("subjectId",m.subjectId().value());n.put("incidentId",m.incidentId().toString());n.put("incidentVersion",m.incidentVersion());n.put("entityIds",m.entityIds().stream().map(e->e.value().toString()).sorted().toList());n.put("metricKeys",m.metricKeys().stream().sorted().toList());n.put("expiresAt",m.expiresAt().toString());n.put("purgedAt",m.purgedAt().toString());n.put("requestDigest",m.requestDigest());return JSON.writeValueAsString(n);}
    public static AiRetention.Marker marker(String raw){try{var n=JSON.readTree(raw);var entities=new HashSet<EntityId>();for(var id:ids(n.get("entityIds"),5))entities.add(new EntityId(id));var keys=new HashSet<String>();for(var k:n.get("metricKeys")){if(!k.isString() || !keys.add(k.asString()))throw new IllegalArgumentException();}
        var m=new AiRetention.Marker(AiRetention.Kind.valueOf(text(n,"kind")),new TenantId(text(n,"tenantId")),uuid(n,"id"),uuid(n,"sessionId"),new SubjectId(text(n,"subjectId")),uuid(n,"incidentId"),number(n,"incidentVersion"),entities,keys,Instant.parse(text(n,"expiresAt")),Instant.parse(text(n,"purgedAt")),n.get("requestDigest").isNull()?null:text(n,"requestDigest"));if(!JSON.readTree(encode(m)).equals(n))throw new IllegalArgumentException();return m;}catch(RuntimeException e){throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);}}
    private static List<UUID> ids(JsonNode n,int max){if(n==null || !n.isArray() || n.size()>max)throw new IllegalArgumentException();var out=new ArrayList<UUID>();for(var id:n){if(!id.isString())throw new IllegalArgumentException();out.add(com.acme.opsweave.platform.incident.IncidentController.uuid(id.asString()));}if(new HashSet<>(out).size()!=out.size())throw new IllegalArgumentException();return out;}
}
