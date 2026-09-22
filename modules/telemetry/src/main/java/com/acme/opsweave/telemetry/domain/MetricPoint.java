package com.acme.opsweave.telemetry.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** A point within a separately identified series. Decimal values preserve unsigned source integers. */
public record MetricPoint(Instant timestamp, BigDecimal value) {
    public MetricPoint {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(value, "value");
        if (value.precision() > 64 || Math.abs((long) value.scale()) > 308
            || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Numeric value exceeds supported bounds");
        }
    }

    public static MetricPoint normalize(Instant timestamp, BigDecimal sourceValue, String transform) {
        new MetricPoint(timestamp, sourceValue);
        BigDecimal value = switch (transform) {
            case "identity" -> sourceValue;
            default -> {
                if (!transform.startsWith("multiply:") || transform.length() > 80) {
                    throw new IllegalArgumentException("Unsupported value transform");
                }
                BigDecimal factor = new BigDecimal(transform.substring("multiply:".length()));
                new MetricPoint(timestamp, factor);
                yield sourceValue.multiply(factor);
            }
        };
        return new MetricPoint(timestamp, value);
    }
}
