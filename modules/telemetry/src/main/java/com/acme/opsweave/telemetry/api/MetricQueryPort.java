package com.acme.opsweave.telemetry.api;

import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;

/** Reads stored samples for one authorized metric. Implementations must not accept a caller-supplied query language. */
public interface MetricQueryPort {
    MetricSeriesResult query(MetricSeriesQuery query);
}
