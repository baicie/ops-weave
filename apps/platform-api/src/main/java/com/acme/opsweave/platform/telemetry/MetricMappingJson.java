package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.domain.MappingDefinition;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.domain.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Wire/storage adapters for the closed mapping maintenance contracts. */
public final class MetricMappingJson {
    private MetricMappingJson() {}
    public static Map<String,Object> pin(MetricMappingPin p) { return p==null?null:Map.of("id",p.id(),"revision",p.revision(),"digest",p.digest()); }
    public static MetricMappingPin pin(JsonNode n) {
        if(n==null)throw new IllegalArgumentException();if(n.isNull())return null;fields(n,Set.of("id","revision","digest"));if(n.size()!=3)throw new IllegalArgumentException();
        return new MetricMappingPin(text(n,"id"),integer(n,"revision",null),text(n,"digest"));
    }
    public static Map<String,Object> binding(MetricBinding b) {
        var body=new LinkedHashMap<String,Object>();body.put("sourceType",b.sourceType());body.put("sourceInstanceId",b.sourceInstanceId());
        body.put("externalItemId",b.externalItemId());body.put("entityId",b.entityId().value().toString());body.put("hostExternalId",b.hostExternalId());
        body.put("metricKey",b.metricKey());body.put("fixedDimensions",b.fixedDimensions());body.put("sourceUnit",b.sourceUnit());
        body.put("valueTransform",b.valueTransform());body.put("mappingRevision",b.mappingRevision());body.put("lifecycle",b.lifecycle().name());
        body.put("version",b.version());body.put("mappingPin",pin(b.mappingPin()));return body;
    }
    private static long version(JsonNode n,String key) {
        var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<1||v.asLong()>1_000_000_000L)throw new IllegalArgumentException();return v.asLong();
    }
    public static MetricBinding binding(TenantId tenant,JsonNode n) {
        fields(n,Set.of("sourceType","sourceInstanceId","externalItemId","entityId","hostExternalId","metricKey","fixedDimensions","sourceUnit","valueTransform","mappingRevision","lifecycle","version","mappingPin"));
        var dims=n.get("fixedDimensions");if(dims==null||!dims.isObject()||dims.size()>32)throw new IllegalArgumentException();var values=new LinkedHashMap<String,String>();
        for(var field:dims.properties()){if(!field.getValue().isString()||field.getKey().length()>64||field.getValue().asString().length()>256)throw new IllegalArgumentException();values.put(field.getKey(),field.getValue().asString());}
        var sourceUnit=n.get("sourceUnit");if(sourceUnit==null||!sourceUnit.isString()||sourceUnit.asString().length()>64)throw new IllegalArgumentException();
        return new MetricBinding(tenant,text(n,"sourceType"),text(n,"sourceInstanceId"),text(n,"externalItemId"),EntityId.parse(text(n,"entityId")),text(n,"hostExternalId"),
            text(n,"metricKey"),values,sourceUnit.asString(),text(n,"valueTransform"),integer(n,"mappingRevision",null),MetricLifecycle.valueOf(text(n,"lifecycle")),version(n,"version"),pin(n.get("mappingPin")));
    }
    public static Map<String,Object> receipt(MetricMappingReceipt r) {
        var n=new LinkedHashMap<String,Object>();n.put("requestId",r.requestId());n.put("expectedBindingVersion",r.expectedBindingVersion());n.put("commandDigest",r.commandDigest());
        n.put("previousPin",pin(r.previousPin()));n.put("binding",binding(r.binding()));n.put("createdAt",r.createdAt().toString());return n;
    }
    public static MetricMappingReceipt receipt(TenantId tenant,JsonNode n) {
        fields(n,Set.of("requestId","expectedBindingVersion","commandDigest","previousPin","binding","createdAt"));
        var raw=text(n,"requestId");var id=UUID.fromString(raw);if(!id.toString().equals(raw))throw new IllegalArgumentException();
        return new MetricMappingReceipt(id,version(n,"expectedBindingVersion"),text(n,"commandDigest"),pin(n.get("previousPin")),binding(tenant,n.get("binding")),Instant.parse(text(n,"createdAt")));
    }
    public static Map<String,Object> definition(MappingDefinition m) {
        var n=new LinkedHashMap<String,Object>();n.put("mappingPin",pin(m.pin()));n.put("connector",m.connector());n.put("sourceKey",m.itemKeyExact());n.put("metricKey",m.metricKey());
        n.put("displayName",m.displayName());n.put("metricType",m.metricType().name());n.put("unit",m.unit());n.put("valueType",m.valueType().name());n.put("dimensionSchema",m.dimensionSchema());
        n.put("fixedDimensions",m.fixedDimensions());n.put("valueTransform",m.valueTransform());n.put("minimum",m.minimum()==null?null:m.minimum().toString());n.put("maximum",m.maximum()==null?null:m.maximum().toString());return n;
    }
}
