package com.acme.opsweave.telemetry.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One series, normalized to millisecond storage precision before any external write. */
public record MetricWriteBatch(Map<String, String> labels, List<Sample> samples, int collapsedPoints) {
    public static final int MAX_POINTS = 4000;

    public MetricWriteBatch {
        labels = Map.copyOf(labels);
        samples = List.copyOf(samples);
        if (labels.isEmpty() || labels.size() > 24 || samples.size() > MAX_POINTS || collapsedPoints < 0) {
            throw new IllegalArgumentException("INVALID_METRIC_BATCH");
        }
        labels.forEach((key, value) -> {
            if (!key.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}") || !value.matches("[a-zA-Z0-9_.:/%\\-]{1,128}")) {
                throw new IllegalArgumentException("UNSUPPORTED_METRIC_LABEL");
            }
        });
        long previous = -1;
        for (Sample sample : samples) {
            if (sample.timestampMillis() <= previous) throw new IllegalArgumentException("INVALID_METRIC_BATCH");
            previous = sample.timestampMillis();
        }
    }

    public static MetricWriteBatch from(Map<String, String> labels, List<MetricPoint> points) {
        if (points.size() > MAX_POINTS) throw new IllegalArgumentException("POINT_BUDGET_EXCEEDED");
        TreeMap<Long, BigDecimal> samples = new TreeMap<>();
        for (MetricPoint point : points) {
            long millis = point.timestamp().toEpochMilli();
            new Sample(millis, point.value());
            BigDecimal previous = samples.putIfAbsent(millis, point.value());
            if (previous != null && previous.compareTo(point.value()) != 0) {
                throw new IllegalArgumentException("MILLISECOND_COLLISION");
            }
        }
        return new MetricWriteBatch(labels, samples.entrySet().stream()
            .map(entry -> new Sample(entry.getKey(), entry.getValue())).toList(), points.size() - samples.size());
    }

    public String seriesHash() {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(labels).forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public record Sample(long timestampMillis, BigDecimal value) {
        public Sample {
            if (timestampMillis < 0 || !Double.isFinite(value.doubleValue())
                || BigDecimal.valueOf(value.doubleValue()).compareTo(value) != 0) {
                throw new IllegalArgumentException("VALUE_PRECISION_LOSS");
            }
        }
    }
}
