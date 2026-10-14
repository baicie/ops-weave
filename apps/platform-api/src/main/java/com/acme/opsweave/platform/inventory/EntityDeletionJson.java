package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.EntityDeletion;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.EntityId;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.acme.opsweave.platform.integration.PipelineJson.fields;
import static com.acme.opsweave.platform.integration.PipelineJson.integer;
import static com.acme.opsweave.platform.integration.PipelineJson.text;

/** Closed wire adapter for the operational delete command and its immutable receipt. */
public final class EntityDeletionJson {
    private EntityDeletionJson() {}

    /** The command is a closed set: no tenant, actor, permission, cascade or dry-run flag. */
    public static EntityDeletion.Command command(JsonNode n) {
        fields(n, Set.of("requestId", "entityId", "reason"));
        return new EntityDeletion.Command(UUID.fromString(text(n, "requestId")),
            new EntityId(UUID.fromString(text(n, "entityId"))), text(n, "reason"));
    }

    public static String storage(EntityDeletion.Receipt receipt) {
        return CatalogJson.JSON.writeValueAsString(wire(receipt));
    }

    public static EntityDeletion.Receipt receipt(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "requestId", "commandDigest", "createdAt", "entityId", "outcome",
            "auditCount", "keptProtected", "keptUnfinished", "reason"));
        if (n.has("schemaVersion") && !"1.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException();
        return new EntityDeletion.Receipt(UUID.fromString(text(n, "requestId")), text(n, "commandDigest"),
            Instant.parse(text(n, "createdAt")), new EntityId(UUID.fromString(text(n, "entityId"))),
            EntityDeletion.Outcome.valueOf(text(n, "outcome")), integer(n, "auditCount", null),
            integer(n, "keptProtected", null), integer(n, "keptUnfinished", null), text(n, "reason"));
    }

    /** Immutable: a replay and a later read return this exact same body. */
    public static Map<String, Object> wire(EntityDeletion.Receipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("requestId", receipt.requestId());
        body.put("commandDigest", receipt.commandDigest());
        body.put("createdAt", receipt.createdAt());
        body.put("entityId", receipt.entityId().value());
        body.put("outcome", receipt.outcome().name());
        body.put("summary", receipt.outcome().summary());
        body.put("auditCount", receipt.auditCount());
        body.put("keptProtected", receipt.keptProtected());
        body.put("keptUnfinished", receipt.keptUnfinished());
        body.put("reason", receipt.reason());
        return body;
    }
}
