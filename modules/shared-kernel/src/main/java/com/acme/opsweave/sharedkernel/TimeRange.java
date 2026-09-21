package com.acme.opsweave.sharedkernel;

import java.time.Instant;
import java.util.Objects;

/** A half-open interval: [from, to). */
public record TimeRange(Instant from, Instant to) {
    public TimeRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must precede to");
    }
}
