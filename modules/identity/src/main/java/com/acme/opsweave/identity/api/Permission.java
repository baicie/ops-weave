package com.acme.opsweave.identity.api;

/** HTTP-facing permission names. The domain enum is authoritative. */
public final class Permission {
    private Permission() {}

    public static final String ENTITY_READ = "entity.read";
    public static final String METRIC_READ = "metric.read";
    public static final String INCIDENT_READ = "incident.read";
    public static final String EVIDENCE_READ = "evidence.read";
    public static final String AI_DIAGNOSE = "ai.diagnose";
    public static final String SKILL_READ = "skill.read";
    public static final String SKILL_MANAGE = "skill.manage";
    public static final String SOURCE_SYNC = "source.sync";
}
