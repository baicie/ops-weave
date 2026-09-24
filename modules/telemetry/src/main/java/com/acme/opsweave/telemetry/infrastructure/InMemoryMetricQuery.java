package com.acme.opsweave.telemetry.infrastructure;

import com.acme.opsweave.telemetry.api.MetricQueryPort;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.MetricSeries;
import java.util.List;

/** Explicit fixture. An empty list is no data, not a failed store. */
public final class InMemoryMetricQuery implements MetricQueryPort {
    private final List<MetricSeries> series;

    public InMemoryMetricQuery(List<MetricSeries> series) {
        this.series = List.copyOf(series);
    }

    @Override
    public MetricSeriesResult query(MetricSeriesQuery query) {
        return MetricSeriesResult.compose(query, series, false);
    }
}
