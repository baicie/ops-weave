package com.acme.opsweave.integration.domain;

import java.time.Instant;

/** Exclusive position for one tenant/source/item and a fixed query window; not a durable checkpoint. */
public record HistoryCursor(long clock, int ns) implements Comparable<HistoryCursor> {
    public HistoryCursor {
        if (clock < 0 || clock > 9_999_999_999L || ns < 0 || ns > 999_999_999) {
            throw new IllegalArgumentException("Invalid history position");
        }
    }

    public Instant instant() {
        return Instant.ofEpochSecond(clock, ns);
    }

    @Override
    public int compareTo(HistoryCursor other) {
        int seconds = Long.compare(clock, other.clock);
        return seconds == 0 ? Integer.compare(ns, other.ns) : seconds;
    }
}
