package com.acme.opsweave.telemetry.domain;

/** Exact executable mapping identity. A revision number alone cannot prove unchanged semantics. */
public record MetricMappingPin(String id, int revision, String digest) {
    public MetricMappingPin {
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")||revision<1||revision>1_000_000
            ||digest==null||!digest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid metric mapping pin");
    }
}
