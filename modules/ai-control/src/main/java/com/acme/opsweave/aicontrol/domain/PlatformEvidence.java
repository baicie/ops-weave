package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

/** Immutable bounded snapshot; payload is assembled by domain code, serialized only at the boundary. */
public record PlatformEvidence(UUID id, UUID sessionId, TenantId tenantId, UUID incidentId, long incidentVersion,
        Set<EntityId> entityIds, String kind, String summary, ToolWindow queryWindow, Instant observedAt,
        Instant availableAt, Instant expiresAt, List<String> dataModes, List<String> warnings, Map<String,Object> data) {
    public PlatformEvidence {
        Objects.requireNonNull(id); Objects.requireNonNull(sessionId); Objects.requireNonNull(tenantId); Objects.requireNonNull(incidentId);
        Objects.requireNonNull(queryWindow); Objects.requireNonNull(observedAt); Objects.requireNonNull(availableAt); Objects.requireNonNull(expiresAt);
        entityIds = Set.copyOf(entityIds); dataModes = List.copyOf(dataModes); warnings = List.copyOf(warnings); data = freezeMap(data, 0);
        if (incidentVersion < 1 || entityIds.size() > ToolReadSession.MAX_ENTITIES || !Set.of("incident", "metric").contains(kind)
            || summary == null || summary.isBlank() || summary.length() > 2000 || observedAt.isAfter(availableAt)
            || !expiresAt.equals(availableAt.plus(Duration.ofHours(24))) || dataModes.size() > 3 || dataModes.isEmpty()
            || !Set.of("labeled-fixture", "zabbix-jsonrpc", "unknown").containsAll(dataModes) || new HashSet<>(dataModes).size() != dataModes.size()
            || warnings.size() > 32 || warnings.stream().anyMatch(v -> v == null || !v.matches("[A-Z_]{1,64}"))) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }
    public void checkLive(Instant now) { if (!now.isBefore(expiresAt)) throw new ToolFailure(ToolFailure.Code.EXPIRED); }
    private static Map<String,Object> freezeMap(Map<String,Object> input, int depth) {
        if (input == null || input.size() > 32 || depth > 8) throw new ToolFailure(ToolFailure.Code.READ_LIMIT);
        var copy = new LinkedHashMap<String,Object>(); input.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            copy.put(key, freeze(value, depth + 1));
        }); return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value, int depth) {
        if (depth > 8) throw new ToolFailure(ToolFailure.Code.READ_LIMIT);
        if (value instanceof String text && text.length() <= 2000) return text;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof Long || value instanceof Boolean) return value;
        if (value instanceof Map<?,?> map) {
            var typed = new LinkedHashMap<String,Object>(); for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST); typed.put(key, entry.getValue());
            } return freezeMap(typed, depth);
        }
        if (value instanceof List<?> list && list.size() <= 50) return list.stream().map(v -> freeze(v, depth + 1)).toList();
        throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }
}
