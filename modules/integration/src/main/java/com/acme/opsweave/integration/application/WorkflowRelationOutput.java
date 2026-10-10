package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.domain.WorkflowRuntime;
import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.util.*;

/** RELATION typed workflow output. Endpoints resolve only through the registered workflow entity
 *  namespace; unresolved endpoints stay pending and nothing is guessed from names or addresses.
 *  Writes reuse the relation uniqueness, cardinality and idempotency rules; a duplicate or
 *  terminated relation fails closed instead of silently winning. */
public final class WorkflowRelationOutput {
    /** Deterministic lookup into registered external links; never name or address matching. */
    public interface Endpoints {
        Optional<Endpoint> resolve(TenantId tenant, String namespace, String identity, String endpointModelId);
    }
    public record Endpoint(EntityId id, long version) {}
    /** Exactly one outcome per row: either a written relation id or a pending code, never both. */
    public record Outcome(String relationId, String pendingCode) {
        public Outcome {
            if ((relationId == null) == (pendingCode == null)) throw new IllegalArgumentException("Relation outcome is exclusive");
            if (relationId != null) UUID.fromString(relationId);
            if (pendingCode != null && !Set.of("FROM_UNRESOLVED", "TO_UNRESOLVED").contains(pendingCode)) throw new IllegalArgumentException("Unknown relation pending code");
        }
    }
    public record Result(List<String> relationIds, List<WorkflowRuntime.Pending> pending) {
        public Result {
            relationIds = List.copyOf(relationIds);
            pending = List.copyOf(pending);
        }
    }

    private final EntityRelationService relations;
    private final Endpoints endpoints;
    private final Clock clock;

    public WorkflowRelationOutput(EntityRelationService relations, Endpoints endpoints, Clock clock) {
        this.relations = Objects.requireNonNull(relations);
        this.endpoints = Objects.requireNonNull(endpoints);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result write(Principal p, String namespace, String relationType, int relationRevision,
            String fromField, String toField, String fromModelId, String toModelId,
            List<Map<String, Object>> rows, String origin) {
        var ids = new ArrayList<String>();
        var pending = new ArrayList<WorkflowRuntime.Pending>();
        for (int i = 0; i < rows.size(); i++) {
            var outcome = writeRow(p, namespace, relationType, relationRevision, fromField, toField, fromModelId, toModelId, rows.get(i), origin);
            if (outcome.relationId() != null) ids.add(outcome.relationId()); else pending.add(new WorkflowRuntime.Pending(i, outcome.pendingCode()));
        }
        return new Result(ids, pending);
    }

    /** One source row, one deterministic requestId: replay across executions is idempotent. */
    public Outcome writeRow(Principal p, String namespace, String relationType, int relationRevision,
            String fromField, String toField, String fromModelId, String toModelId,
            Map<String, Object> row, String origin) {
        if (!Set.of("MANUAL_SAMPLE", "fixture", "zabbix-jsonrpc").contains(origin)) throw new IllegalArgumentException("Unsupported relation output origin");
        if (namespace == null || namespace.isBlank() || namespace.length() > 96) throw new IllegalArgumentException("Invalid relation output namespace");
        var sourceRef = "workflow:" + namespace;
        if (sourceRef.length() > 128) throw new IllegalArgumentException("Invalid relation source reference");
        var fromIdentity = identity(row, fromField);
        var toIdentity = identity(row, toField);
        var left = endpoints.resolve(p.tenantId(), namespace, fromIdentity, fromModelId);
        var right = endpoints.resolve(p.tenantId(), namespace, toIdentity, toModelId);
        if (left.isEmpty() || right.isEmpty()) return new Outcome(null, left.isEmpty() ? "FROM_UNRESOLVED" : "TO_UNRESOLVED");
        var requestId = UUID.nameUUIDFromBytes((p.tenantId().value() + "|workflow-relation|" + namespace + "|" + relationType + "|" + relationRevision
            + "|" + fromIdentity + "|" + toIdentity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var receipt = relations.write(p, requestId, relationType, relationRevision, left.get().id(), right.get().id(),
            clock.instant(), null, sourceRef, dataMode(origin), left.get().version(), right.get().version());
        return new Outcome(receipt.relation().id().toString(), null);
    }

    private static String identity(Map<String, Object> row, String field) {
        var value = row.get(field);
        if (value == null || !(value instanceof String || value instanceof Number || value instanceof Boolean)
            || value.toString().isBlank() || value.toString().length() > 256) throw new IllegalArgumentException("Explicit relation endpoint identity required");
        return value.toString();
    }

    private static String dataMode(String origin) {
        return switch (origin) {
            case "fixture" -> "fixture";
            case "zabbix-jsonrpc" -> "zabbix-jsonrpc";
            default -> "unknown";
        };
    }
}
