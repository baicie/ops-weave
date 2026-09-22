package com.acme.opsweave.integration.domain;

import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies a mapping registry to one Zabbix item. The mapper does not own item-key to metric-key rules.
 * This does not read or store history points.
 */
public final class ZabbixItemMapper {
    private final MappingRegistry mappings;

    public ZabbixItemMapper(MappingRegistry mappings) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
    }

    public Optional<String> rejectReason(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        if (text(payload.get("itemid")).isBlank()) {
            return Optional.of("missing itemid");
        }
        if (text(payload.get("key_")).isBlank()) {
            return Optional.of("missing item key");
        }
        if (text(payload.get("hostid")).isBlank()) {
            return Optional.of("missing hostid");
        }
        Optional<MappingDefinition> mapping = mappings.find("zabbix", text(payload.get("key_")));
        if (mapping.isEmpty()) {
            return Optional.of("unmapped item key");
        }
        if (!compatible(mapping.get().valueType(), valueType(payload.get("value_type")))) {
            return Optional.of("item value type does not match mapping");
        }
        return Optional.empty();
    }

    public MappedMetric map(TenantId tenantId, String sourceInstanceId, Map<String, Object> payload) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        rejectReason(payload).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        MappingDefinition mapping = mappings.find("zabbix", text(payload.get("key_"))).orElseThrow();
        String itemId = text(payload.get("itemid"));
        String hostId = text(payload.get("hostid"));
        var entityId = EntityIds.fromExternal(new ExternalObjectKey(
            tenantId, sourceInstanceId, "host", hostId, ZabbixHostMapper.GENERATION
        ));
        MetricDefinition definition = new MetricDefinition(
            tenantId,
            mapping.metricKey(),
            mapping.displayName(),
            mapping.unit(),
            mapping.valueType(),
            mapping.metricType(),
            mapping.dimensionSchema(),
            1
        );
        MetricBinding binding = new MetricBinding(
            tenantId,
            mapping.connector(),
            sourceInstanceId,
            itemId,
            entityId,
            hostId,
            mapping.metricKey(),
            mapping.fixedDimensions(),
            text(payload.get("units")),
            mapping.valueTransform(),
            mapping.mappingRevision(),
            MetricLifecycle.ACTIVE,
            1
        );
        return new MappedMetric(definition, binding);
    }

    private static boolean compatible(MetricValueType expected, MetricValueType actual) {
        return switch (expected) {
            case DOUBLE, INTEGER -> actual == MetricValueType.DOUBLE || actual == MetricValueType.INTEGER;
            case STRING -> actual == MetricValueType.STRING;
            case BOOLEAN -> actual == MetricValueType.BOOLEAN;
            case UNKNOWN -> false;
        };
    }

    private static MetricValueType valueType(Object raw) {
        return switch (text(raw)) {
            case "0", "float", "double" -> MetricValueType.DOUBLE;
            case "3", "unsigned", "integer" -> MetricValueType.INTEGER;
            case "1", "2", "4", "char", "log", "text" -> MetricValueType.STRING;
            default -> MetricValueType.UNKNOWN;
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
