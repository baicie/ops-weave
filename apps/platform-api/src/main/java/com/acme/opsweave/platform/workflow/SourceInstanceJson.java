package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.SourceInstance;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class SourceInstanceJson {
    private SourceInstanceJson() {}
    public static Map<String,Object> wire(SourceInstance i){return Map.ofEntries(
        Map.entry("id",i.id()),Map.entry("name",i.name()),Map.entry("description",i.description()),Map.entry("source",WorkflowJson.wire(i.source())),
        Map.entry("configurationRevision",i.configurationRevision()),Map.entry("connectionDigest",i.connectionDigest()),Map.entry("dataMode",i.dataMode()),
        Map.entry("editVersion",i.editVersion()),Map.entry("state",i.state()),Map.entry("createdAt",i.createdAt().toString()),Map.entry("updatedAt",i.updatedAt().toString()),Map.entry("workflowId",i.workflowId()));}
    public static SourceInstance instance(String json){return instance(CatalogJson.JSON.readTree(json));}
    public static SourceInstance instance(JsonNode n){fields(n,Set.of("id","name","description","source","configurationRevision","connectionDigest","dataMode","editVersion","state","createdAt","updatedAt","workflowId"));
        var i=new SourceInstance(UUID.fromString(text(n,"id")),text(n,"name"),SourceSetupJson.description(n),SourceSetupJson.source(n.get("source")),integer(n,"configurationRevision",null),text(n,"connectionDigest"),text(n,"dataMode"),integer(n,"editVersion",null),text(n,"state"),Instant.parse(text(n,"createdAt")),Instant.parse(text(n,"updatedAt")));
        if(!i.workflowId().equals(text(n,"workflowId")))throw new IllegalArgumentException();return i;}
    public static SourceInstance.Configuration configuration(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("sourceId","revision","connectionDigest","dataMode","createdAt"));return new SourceInstance.Configuration(UUID.fromString(text(n,"sourceId")),integer(n,"revision",null),text(n,"connectionDigest"),text(n,"dataMode"),Instant.parse(text(n,"createdAt")));}
    public static Map<String,Object> wire(SourceInstance.CommandReceipt r){return Map.of("requestId",r.requestId(),"sourceId",r.sourceId(),"commandDigest",r.commandDigest(),"instance",wire(r.instance()));}
    public static SourceInstance.CommandReceipt receipt(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","sourceId","commandDigest","instance"));return new SourceInstance.CommandReceipt(UUID.fromString(text(n,"requestId")),UUID.fromString(text(n,"sourceId")),text(n,"commandDigest"),instance(n.get("instance")));}
}
