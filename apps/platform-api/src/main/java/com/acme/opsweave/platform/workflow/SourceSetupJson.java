package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class SourceSetupJson {
    private SourceSetupJson() {}
    public static WorkflowDefinition.Source source(JsonNode n) {fields(n,Set.of("kind","instanceId"));return new WorkflowDefinition.Source(text(n,"kind"),text(n,"instanceId"));}
    public static WorkflowDefinition.Target target(JsonNode n) {fields(n,Set.of("id","revision","digest"));return new WorkflowDefinition.Target(text(n,"id"),integer(n,"revision",null),text(n,"digest"));}
    public static Map<String,Object> wire(SourceSetup s) {var v=new LinkedHashMap<String,Object>();v.put("id",s.id().toString());v.put("name",s.name());v.put("description",s.description());v.put("source",WorkflowJson.wire(s.source()));v.put("connectionDigest",s.connectionDigest());v.put("dataMode",s.dataMode());v.put("initialTarget",s.initialTarget()==null?null:WorkflowJson.wire(s.initialTarget()));v.put("digest",s.digest());v.put("createdAt",s.createdAt().toString());v.put("workflowId",s.workflowId());return v;}
    public static SourceSetup decode(String json) {var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("id","name","description","source","connectionDigest","dataMode","initialTarget","digest","createdAt","workflowId"));var s=new SourceSetup(UUID.fromString(text(n,"id")),text(n,"name"),description(n),source(n.get("source")),text(n,"connectionDigest"),text(n,"dataMode"),n.get("initialTarget").isNull()?null:target(n.get("initialTarget")),text(n,"digest"),Instant.parse(text(n,"createdAt")));if(!s.workflowId().equals(text(n,"workflowId")))throw new IllegalArgumentException();return s;}
    public static String description(JsonNode n) {var value=n.get("description");if(value==null||!value.isString()||value.asString().length()>500)throw new IllegalArgumentException();return value.asString();}
}
