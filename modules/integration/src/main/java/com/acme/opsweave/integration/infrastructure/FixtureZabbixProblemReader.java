package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.domain.ProblemReadWindow;
import java.math.BigInteger;
import java.time.*;
import java.util.List;

/** Explicit development fixture; IDs and occurrence times are stable across reads. */
public final class FixtureZabbixProblemReader implements ZabbixProblemPort {
    private final Clock clock;
    public FixtureZabbixProblemReader(Clock clock) { this.clock = clock; }
    public Page read(Connector.SourceContext source, ProblemReadWindow window) {
        Instant start = Instant.parse("2026-09-21T12:00:00Z"); Instant observed = clock.instant();
        var rows = List.of(
            new ExternalProblem(source.tenantId(), source.sourceInstanceId(), "30001", "40001", "Fixture: CPU threshold exceeded", 3, start,
                observed, List.of("10084"), false, "30003", start.plusSeconds(600)),
            new ExternalProblem(source.tenantId(), source.sourceInstanceId(), "30002", "40002", "Fixture: service unavailable", 4, start.plusSeconds(300),
                observed, List.of("10085"), true, null, null));
        var filtered = rows.stream().filter(p -> p.occurredAt().getEpochSecond() <= window.till()
            && (p.recoveredAt() == null || p.recoveredAt().getEpochSecond() >= window.from())
            && (window.afterEventId() == null || new BigInteger(p.problemEventId()).compareTo(new BigInteger(window.afterEventId())) > 0)).toList();
        var items = filtered.stream().limit(window.limit()).toList();
        return new Page(items, filtered.size() > window.limit() ? items.getLast().problemEventId() : null);
    }
}
