package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class SourceConnectionJson {
    private SourceConnectionJson() {}
    public static UUID uuid(JsonNode n,String key){return uuid(text(n,key));}
    public static UUID uuid(String raw){var id=UUID.fromString(raw);if(!raw.equals(id.toString()))throw new IllegalArgumentException();return id;}
    public static SourceEndpoint.Pin endpointPin(JsonNode n){fields(n,Set.of("id","digest"));return new SourceEndpoint.Pin(text(n,"id"),text(n,"digest"));}
    public static SourceCredential.Pin credentialPin(JsonNode n){fields(n,Set.of("credentialId","revision","versionId"));return new SourceCredential.Pin(uuid(n,"credentialId"),integer(n,"revision",null),uuid(n,"versionId"));}
    public static Map<String,Object> wire(SourceConnectionConfiguration c){var n=new LinkedHashMap<String,Object>();n.put("sourceId",c.sourceId());n.put("revision",c.revision());n.put("connectorVersion",c.connectorVersion());n.put("endpoint",c.endpoint());n.put("credentialPin",c.credentialPin());if(c.scoped())n.put("hostGroupIds",c.hostGroupIds());n.put("connectionDigest",c.connectionDigest());n.put("createdAt",c.createdAt().toString());return n;}
    public static SourceConnectionConfiguration configuration(String json){
        var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("sourceId","revision","connectorVersion","endpoint","credentialPin","hostGroupIds","connectionDigest","createdAt"));
        String version=text(n,"connectorVersion");List<String> groups=List.of();
        if(n.has("hostGroupIds")){var value=n.get("hostGroupIds");if(!value.isArray())throw new IllegalArgumentException();var parsed=new ArrayList<String>();for(var item:value){if(!item.isTextual())throw new IllegalArgumentException();parsed.add(item.textValue());}groups=SourceConnectionConfiguration.normalizeHostGroupIds(parsed);if(!groups.equals(parsed))throw new IllegalArgumentException();}
        if(SourceConnectionConfiguration.LEGACY_VERSION.equals(version)&&n.has("hostGroupIds"))throw new IllegalArgumentException();
        if(SourceConnectionConfiguration.VERSION.equals(version)&&!n.has("hostGroupIds"))throw new IllegalArgumentException();
        var e=n.get("endpoint");fields(e,Set.of("id","name","connectorKind","address","digest"));
        return new SourceConnectionConfiguration(uuid(n,"sourceId"),integer(n,"revision",null),version,new SourceEndpoint(text(e,"id"),text(e,"name"),text(e,"connectorKind"),text(e,"address"),text(e,"digest")),credentialPin(n.get("credentialPin")),groups,text(n,"connectionDigest"),Instant.parse(text(n,"createdAt")));
    }
}
