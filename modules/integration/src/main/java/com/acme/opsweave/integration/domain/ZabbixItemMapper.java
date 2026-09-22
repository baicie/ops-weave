package com.acme.opsweave.integration.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.ExternalMetricMapping;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricOrigin;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps one Zabbix item onto a metric definition. Unknown keys are rejected and are not invented metrics.
 * This does not read or store history points.
 */
public final class ZabbixItemMapper {
    public static final String CPU_USER_KEY = "system.cpu.util[,user]";
    public static final String CPU_USER_METRIC = "host.cpu.usage.user";
    public static final String CPU_USER_UNIT = "1";
    public static final String CPU_USER_TRANSFORM = "multiply:0.01";
    public static final int REVISION = 1;

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
        if (!CPU_USER_KEY.equals(text(payload.get("key_")))) {
            return Optional.of("unmapped item key");
        }
        if (valueType(payload.get("value_type")) != MetricValueType.DOUBLE
            && valueType(payload.get("value_type")) != MetricValueType.INTEGER) {
            return Optional.of("cpu item must be numeric");
        }
        return Optional.empty();
    }

    public MetricDefinition map(TenantId tenantId, String sourceInstanceId, Map<String, Object> payload) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        rejectReason(payload).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        String itemId = text(payload.get("itemid"));
        String hostId = text(payload.get("hostid"));
        String displayName = text(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = CPU_USER_METRIC;
        }
        String id = UUID.nameUUIDFromBytes(
            (tenantId.value() + "|zabbix|" + sourceInstanceId + "|item|" + itemId).getBytes(StandardCharsets.UTF_8)
        ).toString();
        return new MetricDefinition(
            id,
            tenantId,
            CPU_USER_METRIC,
            displayName,
            "host",
            CPU_USER_UNIT,
            valueType(payload.get("value_type")),
            MetricType.GAUGE,
            Map.of("mode", "user"),
            MetricOrigin.SOURCE,
            new ExternalMetricMapping(
                "zabbix",
                sourceInstanceId,
                itemId,
                CPU_USER_KEY,
                hostId,
                text(payload.get("units")),
                CPU_USER_TRANSFORM,
                REVISION
            ),
            MetricLifecycle.ACTIVE,
            1
        );
    }

    private static MetricValueType valueType(Object raw) {
        return switch (text(raw)) {
            case "0", "float", "double" -> MetricValueType.DOUBLE;
            case "3", "unsigned", "integer" -> MetricValueType.INTEGER;
            case "1", "2", "4", "char", "log", "text" -> MetricValueType.STRING;
            case "" -> MetricValueType.UNKNOWN;
            default -> MetricValueType.UNKNOWN;
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
