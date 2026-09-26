package com.acme.opsweave.inventory.domain;

import java.time.Instant;
import java.util.Objects;

/** Bounded retained history, ordered by opaque observation ID; not a database snapshot. */
public record ObservationQuery(long from, long till, Instant asOf, String source, String after, int limit) {
    public ObservationQuery {
        Objects.requireNonNull(asOf); Objects.requireNonNull(source);
        if (from < 0 || till < from || till - from > 31 * 86400L || till > asOf.getEpochSecond()
            || asOf.getEpochSecond() > 253402300799L || limit < 1 || limit > 50
            || (!source.isEmpty() && !source.matches("[a-zA-Z0-9_.:-]{1,128}"))
            || (after != null && !after.matches("[a-zA-Z0-9_.:-]{1,128}"))) throw new IllegalArgumentException("Invalid observation query");
    }
    public boolean includes(Observation observation) {
        return !observation.observedAt().isBefore(Instant.ofEpochSecond(from))
            && !observation.observedAt().isAfter(Instant.ofEpochSecond(till)) && !observation.ingestedAt().isAfter(asOf)
            && (source.isEmpty() || source.equals(observation.key().sourceInstanceId()))
            && (after == null || observation.id().compareTo(after) > 0);
    }
}
