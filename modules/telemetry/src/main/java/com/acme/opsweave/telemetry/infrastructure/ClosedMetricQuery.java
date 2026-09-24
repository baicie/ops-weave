package com.acme.opsweave.telemetry.infrastructure;

import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.api.MetricQueryPort;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;

/** No time-series endpoint is configured. This is not an empty result. */
public final class ClosedMetricQuery implements MetricQueryPort {
    @Override
    public MetricSeriesResult query(MetricSeriesQuery query) {
        throw new MetricQueryException(MetricQueryException.Code.SOURCE_UNAVAILABLE);
    }
}
