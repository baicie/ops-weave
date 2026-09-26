package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.inventory.api.ObservationReader;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.util.List;

public final class ReadObservationsUseCase {
    private final GetEntityUseCase entities;
    private final ObservationReader reader;
    private final Clock clock;
    public ReadObservationsUseCase(GetEntityUseCase entities, ObservationReader reader, Clock clock) {
        this.entities = entities; this.reader = reader; this.clock = clock;
    }
    public Page execute(Principal principal, EntityId entity, ObservationQuery query) {
        var access = entities.get(principal, entity);
        if (access.kind() != EntityAccessKind.FOUND) return new Page(access.kind(), List.of(), null);
        if (query.asOf().isAfter(clock.instant())) throw new IllegalArgumentException("Future observation cutoff");
        var rows = reader.observations(principal.tenantId(), entity, query);
        if (rows.size() > query.limit() + 1) throw new IllegalStateException("Observation page exceeds budget");
        String previous = query.after() == null ? "" : query.after();
        for (var row : rows) {
            var observation = row.observation();
            if (!observation.key().tenantId().equals(principal.tenantId()) || !observation.entityId().equals(entity)
                || !query.includes(observation) || observation.id().compareTo(previous) <= 0) throw new IllegalStateException("Observation scope mismatch");
            EntityReadLimits.check(observation.fields()); previous = observation.id();
        }
        boolean more = rows.size() > query.limit();
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), query.limit())));
        return new Page(EntityAccessKind.FOUND, items, more ? items.getLast().observation().id() : null);
    }
    public record Page(EntityAccessKind kind, List<ObservationReader.Entry> items, String nextCursor) {}
}
