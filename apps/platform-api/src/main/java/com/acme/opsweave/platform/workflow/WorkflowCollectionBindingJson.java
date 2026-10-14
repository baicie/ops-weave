package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowCollectionBinding;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.acme.opsweave.platform.integration.PipelineJson.fields;
import static com.acme.opsweave.platform.integration.PipelineJson.integer;
import static com.acme.opsweave.platform.integration.PipelineJson.text;

/** Closed wire adapter for the collection binding command, binding and immutable receipt. */
public final class WorkflowCollectionBindingJson {
    private WorkflowCollectionBindingJson() {}

    /** The command is a closed set: no tenant, actor, permission, address or background authority. */
    public static WorkflowCollectionBinding.Command command(JsonNode n) {
        fields(n, Set.of("requestId", "id", "revision", "digest", "sourceInstanceId", "operation", "reason"));
        return new WorkflowCollectionBinding.Command(UUID.fromString(text(n, "requestId")), text(n, "id"),
            integer(n, "revision", null), text(n, "digest"), text(n, "sourceInstanceId"),
            WorkflowCollectionBinding.operation(text(n, "operation")), text(n, "reason"));
    }

    public static WorkflowCollectionBinding.Binding binding(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("id", "revision", "digest", "sourceInstanceId", "state", "generation", "updatedAt", "reason"));
        return new WorkflowCollectionBinding.Binding(text(n, "id"), integer(n, "revision", null), text(n, "digest"),
            text(n, "sourceInstanceId"), WorkflowCollectionBinding.State.valueOf(text(n, "state")),
            integer(n, "generation", null), Instant.parse(text(n, "updatedAt")), text(n, "reason"));
    }

    public static WorkflowCollectionBinding.Receipt receipt(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "requestId", "operation", "commandDigest", "createdAt", "binding"));
        if (n.has("schemaVersion") && !"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException();
        return new WorkflowCollectionBinding.Receipt(UUID.fromString(text(n, "requestId")),
            WorkflowCollectionBinding.Operation.valueOf(text(n, "operation")), text(n, "commandDigest"),
            Instant.parse(text(n, "createdAt")), binding(n.get("binding").toString()));
    }

    public static Map<String, Object> wire(WorkflowCollectionBinding.Binding binding) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", binding.id());
        body.put("revision", binding.revision());
        body.put("digest", binding.digest());
        body.put("sourceInstanceId", binding.sourceInstanceId());
        body.put("state", binding.state().name());
        body.put("generation", binding.generation());
        body.put("updatedAt", binding.updatedAt());
        body.put("reason", binding.reason());
        return body;
    }

    /** The immutable acknowledgement: a replay and a later read return this exact same body. */
    public static Map<String, Object> wire(WorkflowCollectionBinding.Receipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId());
        body.put("operation", receipt.operation().name());
        body.put("commandDigest", receipt.commandDigest());
        body.put("createdAt", receipt.createdAt());
        body.put("binding", wire(receipt.binding()));
        return body;
    }

    /** Bounded readable state: one row per workflow the caller owns, never a hidden mutation. */
    public static Map<String, Object> page(List<WorkflowCollectionBinding.Binding> items) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : items) rows.add(wire(item));
        body.put("items", rows);
        body.put("count", rows.size());
        return body;
    }
}
