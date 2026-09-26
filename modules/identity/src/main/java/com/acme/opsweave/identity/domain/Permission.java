package com.acme.opsweave.identity.domain;

import java.util.Locale;
import java.util.Objects;

/** Wire values are stable; this is not a complete IAM catalog. */
public enum Permission {
    ENTITY_READ("entity.read"),
    ENTITY_MANAGE("entity.manage"),
    METRIC_READ("metric.read"),
    INCIDENT_READ("incident.read"),
    INCIDENT_MANAGE("incident.manage"),
    EVIDENCE_READ("evidence.read"),
    AI_DIAGNOSE("ai.diagnose"),
    AI_INSIGHT_READ("ai.insight.read"),
    AI_RETENTION_MANAGE("ai.retention.manage"),
    SKILL_READ("skill.read"),
    SKILL_MANAGE("skill.manage"),
    SOURCE_SYNC("source.sync");

    private final String wireValue;

    Permission(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Permission fromWire(String value) {
        Objects.requireNonNull(value, "permission");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Permission permission : values()) {
            if (permission.wireValue.equals(normalized)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown permission");
    }
}
