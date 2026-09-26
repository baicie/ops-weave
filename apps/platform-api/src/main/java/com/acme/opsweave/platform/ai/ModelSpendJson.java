package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ModelSpendJson {
    private ModelSpendJson() {}
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static JsonNode read(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length==0 || bytes.length>16384)throw new IllegalArgumentException();
        try{return JSON.readTree(bytes);}catch(RuntimeException invalid){throw new IllegalArgumentException();}
    }
    public static Map<String,Object> policy(ModelSpend.Policy p) {
        return Map.of("provider",p.provider(),"model",p.model(),"priceVersion",p.priceVersion(),"inputMicrosPerMillion",p.inputMicrosPerMillion(),
            "outputMicrosPerMillion",p.outputMicrosPerMillion(),"maxCallMicros",p.maxCallMicros(),"dailyMicros",p.dailyMicros());
    }
    private static Map<String,Object> raw(ModelSpend.Call c) {
        var v=new LinkedHashMap<String,Object>();v.put("schemaVersion","1.0");v.put("tenantId",c.tenantId().value());v.put("subjectId",c.subjectId().value());
        v.put("runId",c.runId().toString());v.put("sessionId",c.sessionId().toString());v.put("incidentId",c.incidentId().toString());
        v.put("inputDigest",c.inputDigest());v.put("inputBytes",c.inputBytes());v.put("policy",policy(c.policy()));
        v.put("reservedAt",c.reservedAt().toString());v.put("deadlineAt",c.deadlineAt().toString());v.put("reportedAt",c.reportedAt()==null?null:c.reportedAt().toString());
        v.put("usage",c.usage()==null?null:Map.of("inputTokens",c.usage().inputTokens(),"outputTokens",c.usage().outputTokens(),"cachedInputTokens",c.usage().cachedInputTokens(),"source",c.usage().source()));return v;
    }
    public static Map<String,Object> wire(ModelSpend.Call c,Instant now) {
        var v=raw(c);v.put("state",c.state(now));v.put("currency","USD");v.put("reservedMicros",c.reservedMicros());
        v.put("estimatedMicros",c.usage()==null?null:c.chargedMicros());v.put("accountedMicros",c.chargedMicros());
        v.put("maxInputTokens",ModelSpend.MAX_INPUT_TOKENS);v.put("maxOutputTokens",ModelSpend.MAX_OUTPUT_TOKENS);v.put("maxInputBytes",ModelSpend.MAX_INPUT_BYTES);
        v.put("accounting","configured-price-estimate");return v;
    }
    public static String encode(ModelSpend.Call c) { return JSON.writeValueAsString(raw(c)); }
    public static ModelSpend.Call decode(String value) {
        try {
            var n=JSON.readTree(value);var p=n.get("policy");
            var policy=new ModelSpend.Policy(text(p,"provider"),text(p,"model"),text(p,"priceVersion"),number(p,"inputMicrosPerMillion"),number(p,"outputMicrosPerMillion"),number(p,"maxCallMicros"),number(p,"dailyMicros"));
            var result=new ModelSpend.Call(new TenantId(text(n,"tenantId")),new SubjectId(text(n,"subjectId")),uuid(n,"runId"),uuid(n,"sessionId"),uuid(n,"incidentId"),
                text(n,"inputDigest"),Math.toIntExact(number(n,"inputBytes")),policy,Instant.parse(text(n,"reservedAt")),Instant.parse(text(n,"deadlineAt")),
                n.get("usage").isNull()?null:usage(n.get("usage")),n.get("reportedAt").isNull()?null:Instant.parse(text(n,"reportedAt")));
            if(!JSON.readTree(encode(result)).equals(n)) throw new IllegalArgumentException(); return result;
        } catch(RuntimeException invalid) { throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
    }
    public static ModelSpend.Usage usage(JsonNode n) {
        exact(n,"inputTokens","outputTokens","cachedInputTokens","source");return new ModelSpend.Usage(integer(n,"inputTokens"),integer(n,"outputTokens"),integer(n,"cachedInputTokens"),text(n,"source"));
    }
    public static void exact(JsonNode n,String... fields) { if(n==null || !n.isObject() || n.size()!=fields.length || Arrays.stream(fields).anyMatch(f->!n.has(f))) throw new IllegalArgumentException(); }
    public static String text(JsonNode n,String key) { if(n==null || n.get(key)==null || !n.get(key).isString()) throw new IllegalArgumentException(); return n.get(key).asString(); }
    public static long number(JsonNode n,String key) { if(n==null || n.get(key)==null || !n.get(key).isIntegralNumber() || !n.get(key).canConvertToLong()) throw new IllegalArgumentException(); return n.get(key).asLong(); }
    public static int integer(JsonNode n,String key) { long value=number(n,key); if(value<0 || value>Integer.MAX_VALUE)throw new IllegalArgumentException();return (int)value; }
    public static UUID uuid(JsonNode n,String key) { return com.acme.opsweave.platform.incident.IncidentController.uuid(text(n,key)); }
}
