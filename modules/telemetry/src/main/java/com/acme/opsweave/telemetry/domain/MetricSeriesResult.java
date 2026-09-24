package com.acme.opsweave.telemetry.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One query result. Separate series keep their source and dimensions.
 * An empty point list is a successful no-data result, not a storage failure.
 */
public record MetricSeriesResult(
    EntityId entityId,
    String metricKey,
    long from,
    long till,
    List<MetricSeries> series,
    Status status
) {
    public MetricSeriesResult {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(metricKey, "metricKey");
        series = List.copyOf(series);
        Objects.requireNonNull(status, "status");
    }

    public static MetricSeriesResult compose(MetricSeriesQuery query, List<MetricSeries> raw, boolean scannedBeyondCap) {
        long startMillis = Math.multiplyExact(query.from(), 1000);
        long endMillis = Math.multiplyExact(query.till() + 1L, 1000);
        List<MetricSeries> normalized = new ArrayList<>();
        for (MetricSeries series : raw) {
            List<Sample> points = series.points().stream()
                .filter(point -> point.timestampMillis() >= startMillis && point.timestampMillis() < endMillis)
                .sorted(Comparator.comparingLong(Sample::timestampMillis))
                .toList();
            if (!points.isEmpty()) normalized.add(series.withPoints(points));
        }
        normalized.sort(Comparator
            .comparing(MetricSeries::sourceInstanceId)
            .thenComparing(MetricSeries::externalItemId)
            .thenComparingLong(MetricSeries::mappingRevision));
        record Located(int seriesIndex, Sample sample) {}
        List<Located> flat = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            for (Sample sample : normalized.get(i).points()) flat.add(new Located(i, sample));
        }
        flat.sort(Comparator.comparingLong(located -> located.sample().timestampMillis()));
        boolean truncated = scannedBeyondCap || flat.size() > query.maxPoints();
        if (flat.size() > query.maxPoints()) flat = flat.subList(flat.size() - query.maxPoints(), flat.size());
        List<List<Sample>> kept = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) kept.add(new ArrayList<>());
        for (Located located : flat) kept.get(located.seriesIndex()).add(located.sample());
        List<MetricSeries> trimmed = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            if (!kept.get(i).isEmpty()) trimmed.add(normalized.get(i).withPoints(kept.get(i)));
        }
        normalized = trimmed;
        Long last = flat.isEmpty() ? null : flat.getLast().sample().timestampMillis();
        boolean fresh = last != null && last >= Math.multiplyExact(query.till() - query.freshnessSeconds(), 1000L);
        Status.Kind kind = flat.isEmpty() ? Status.Kind.NO_DATA : truncated ? Status.Kind.PARTIAL : fresh ? Status.Kind.AVAILABLE : Status.Kind.STALE;
        return new MetricSeriesResult(query.entityId(), query.metricKey(), query.from(), query.till(), normalized,
            new Status(kind, last, fresh, truncated));
    }

    public record MetricSeries(
        String sourceInstanceId,
        String dataMode,
        String externalItemId,
        long mappingRevision,
        String unit,
        Map<String, String> dimensions,
        List<Sample> points
    ) {
        public MetricSeries {
            if (!sourceInstanceId.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")
                || !dataMode.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")
                || !externalItemId.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")
                || !unit.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")
                || mappingRevision < 1) {
                throw new IllegalArgumentException("Invalid metric series identity");
            }
            dimensions = Map.copyOf(new LinkedHashMap<>(dimensions));
            points = List.copyOf(points);
            dimensions.forEach((key, value) -> {
                if (!key.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}") || !value.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")) {
                    throw new IllegalArgumentException("Invalid metric dimension");
                }
            });
        }

        private MetricSeries withPoints(List<Sample> points) {
            return new MetricSeries(sourceInstanceId, dataMode, externalItemId, mappingRevision, unit, dimensions, points);
        }
    }

    public record Sample(long timestampMillis, BigDecimal value) {
        public Sample {
            if (timestampMillis < 0 || value == null) throw new IllegalArgumentException("Invalid metric sample");
        }
    }

    public record Status(Kind kind, Long lastPointAtMillis, boolean fresh, boolean partial) {
        public enum Kind { AVAILABLE, NO_DATA, STALE, PARTIAL }

        public Status {
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.NO_DATA) != (lastPointAtMillis == null) || (kind == Kind.PARTIAL) != partial) {
                throw new IllegalArgumentException("Metric status flags disagree");
            }
            if (kind == Kind.AVAILABLE && !fresh || kind == Kind.STALE && fresh || kind == Kind.NO_DATA && fresh) {
                throw new IllegalArgumentException("Metric freshness disagrees with status");
            }
        }
    }
}
