package com.acme.opsweave.telemetry.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;

/** A bounded read of one entity and catalog metric. Callers cannot supply a storage query language. */
public record MetricSeriesQuery(
    TenantId tenantId,
    EntityId entityId,
    String metricKey,
    long from,
    long till,
    int maxPoints,
    int freshnessSeconds
) {
    public static final int MAX_WINDOW_SECONDS = 3600;
    public static final int MAX_POINTS = 500;

    public MetricSeriesQuery {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(metricKey, "metricKey");
        if (!metricKey.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")) {
            throw new IllegalArgumentException("Invalid metric key");
        }
        if (from < 0 || till < from || till - from > MAX_WINDOW_SECONDS || till > 9_999_999_999L) {
            throw new IllegalArgumentException("Metric query window must be a closed range of at most 3600 seconds");
        }
        if (maxPoints < 1 || maxPoints > MAX_POINTS) {
            throw new IllegalArgumentException("Metric query maxPoints must be between 1 and 500");
        }
        if (freshnessSeconds < 0 || freshnessSeconds > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Metric freshness must be between 0 and 3600 seconds");
        }
    }
}
