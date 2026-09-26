package com.acme.opsweave.telemetry.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit policy for counter (SUM) series. A decrease means the counter restarted, so the interval
 * delta is the current value counted from zero and the interval is marked as a reset; rates are never
 * negative, non-positive intervals are skipped instead of invented, and raw points stay untouched.
 * Deriving a rate never hides a reset: every affected interval carries {@code counterReset = true}.
 */
public final class CounterRatePolicy {
    /** Stable name of the reset rule, exposed with every derived view. */
    public static final String RESET_FROM_ZERO = "reset-counts-from-zero";
    /** Decimal scale of a derived per-second rate. */
    public static final int SCALE = 6;

    private CounterRatePolicy() {}

    public static List<Rate> derive(List<MetricSeriesResult.Sample> points) {
        List<Rate> rates = new ArrayList<>();
        MetricSeriesResult.Sample previous = null;
        for (MetricSeriesResult.Sample current : points) {
            if (previous != null) {
                long intervalMillis = current.timestampMillis() - previous.timestampMillis();
                if (intervalMillis > 0) {
                    BigDecimal delta = current.value().subtract(previous.value());
                    boolean reset = delta.signum() < 0;
                    if (reset) {
                        // The counter restarted: count what it accumulated since the reset.
                        delta = current.value();
                    }
                    if (delta.signum() < 0) {
                        // A counter that is negative is invalid input; skip the interval instead of inventing one.
                        previous = current;
                        continue;
                    }
                    BigDecimal seconds = BigDecimal.valueOf(intervalMillis).movePointLeft(3);
                    rates.add(new Rate(
                        current.timestampMillis(),
                        delta.divide(seconds, SCALE, RoundingMode.HALF_UP),
                        reset
                    ));
                }
            }
            previous = current;
        }
        return List.copyOf(rates);
    }

    /** One derived interval: the rate is per second and never negative. */
    public record Rate(long timestampMillis, BigDecimal perSecond, boolean counterReset) {
        public Rate {
            if (timestampMillis < 0 || perSecond == null || perSecond.signum() < 0) {
                throw new IllegalArgumentException("Invalid counter rate");
            }
        }
    }
}
