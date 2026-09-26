package com.acme.opsweave.incident.domain;

import com.acme.opsweave.alerting.domain.*;
import java.time.Instant;
import java.util.*;

/** Current Incident ownership/version, bounded observed-time window and fixed knowledge cutoff. */
public record ProblemHistoryQuery(long incidentVersion, long from, long till, Instant asOf, String source,
        String eventId, UUID after, int limit) {
    public ProblemHistoryQuery {
        Objects.requireNonNull(asOf); Objects.requireNonNull(source); Objects.requireNonNull(eventId);
        if (incidentVersion < 1 || incidentVersion > 9_007_199_254_740_991L || from < 0 || till > 253402300799L || till < from || till - from > 31 * 86400L
                || Instant.ofEpochSecond(till).isAfter(asOf) || limit < 1 || limit > 25
                || (!source.isEmpty() && !source.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) || (!eventId.isEmpty() && source.isEmpty())) throw new IllegalArgumentException("Invalid problem history query");
        if (!eventId.isEmpty()) ExternalProblem.positiveId(eventId);
    }
    public boolean includes(ProblemObservation entry) {
        var p = entry.observation();
        return !p.observedAt().isBefore(Instant.ofEpochSecond(from)) && !p.observedAt().isAfter(Instant.ofEpochSecond(till)) && !entry.firstReceivedAt().isAfter(asOf)
            && (source.isEmpty() || source.equals(p.sourceInstanceId())) && (eventId.isEmpty() || eventId.equals(p.problemEventId()))
            && (after == null || entry.id().toString().compareTo(after.toString()) > 0);
    }
}
