package com.acme.opsweave.platform.incident;

import com.acme.opsweave.alerting.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict boundary codec for immutable normalized inputs, not the merged Incident projection. */
public final class ProblemHistoryJson {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private ProblemHistoryJson() {}
    public static Map<String,Object> body(ProblemObservation entry) {
        return Map.of("schemaVersion","1.0","id",entry.id().toString(),"observation",IncidentJson.problem(entry.observation()),
            "dataMode",entry.dataMode(),"sourceContract",ProblemObservation.SOURCE_CONTRACT,"firstReceivedAt",entry.firstReceivedAt().toString(),
            "entities",entry.entities().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> Map.of("hostId",e.getKey(),"entityId",e.getValue().value().toString())).toList(),
            "gaps",entry.hasUnmappedHosts()?List.of("ENTITY_MAPPING_MISSING"):List.of());
    }
    public static String encode(ProblemObservation entry) {
        String value=JSON.writeValueAsString(body(entry));
        if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Problem observation too large");
        return value;
    }
    public static ProblemObservation decode(String value) {
        try {
            if(value==null||value.length()>16384)throw new IllegalArgumentException();
            var root=JSON.readTree(value);var p=root.get("observation");var hosts=new ArrayList<String>();
            if(!p.get("hostIds").isArray()||p.get("hostIds").size()>20||!root.get("entities").isArray()||root.get("entities").size()>20)throw new IllegalArgumentException();
            for(var h:p.get("hostIds")){if(!h.isString())throw new IllegalArgumentException();hosts.add(h.asString());}
            if(!p.get("suppressed").isBoolean()||!p.get("severity").isIntegralNumber()||!p.get("severity").canConvertToInt())throw new IllegalArgumentException();
            var problem=new ExternalProblem(new TenantId(text(p,"tenantId")),text(p,"sourceInstanceId"),text(p,"problemEventId"),text(p,"triggerId"),text(p,"title"),p.get("severity").asInt(),
                Instant.parse(text(p,"occurredAt")),Instant.parse(text(p,"observedAt")),hosts,p.get("suppressed").asBoolean(),nullable(p,"recoveryEventId"),nullable(p,"recoveredAt")==null?null:Instant.parse(text(p,"recoveredAt")));
            var entities=new HashMap<String,EntityId>();
            for(var entity:root.get("entities"))if(entities.putIfAbsent(text(entity,"hostId"),EntityId.parse(text(entity,"entityId")))!=null)throw new IllegalArgumentException();
            var entry=new ProblemObservation(UUID.fromString(text(root,"id")),problem,text(root,"dataMode"),Instant.parse(text(root,"firstReceivedAt")),entities);
            if(!JSON.readTree(encode(entry)).equals(root))throw new IllegalArgumentException();
            return entry;
        }catch(RuntimeException invalid){throw new IllegalStateException("Invalid stored problem observation");}
    }
    private static String text(JsonNode n,String key){if(n.get(key)==null||!n.get(key).isString())throw new IllegalArgumentException();return n.get(key).asString();}
    private static String nullable(JsonNode n,String key){if(n.get(key)==null)throw new IllegalArgumentException();return n.get(key).isNull()?null:text(n,key);}
}
