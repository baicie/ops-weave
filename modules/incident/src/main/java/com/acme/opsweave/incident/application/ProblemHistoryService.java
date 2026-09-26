package com.acme.opsweave.incident.application;

import com.acme.opsweave.alerting.domain.ProblemObservation;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.incident.api.ProblemHistoryReader;
import com.acme.opsweave.incident.domain.*;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

public final class ProblemHistoryService {
    private final IncidentService incidents;
    private final ProblemHistoryReader reader;
    private final Clock clock;
    public ProblemHistoryService(IncidentService incidents, ProblemHistoryReader reader, Clock clock) { this.incidents=incidents;this.reader=reader;this.clock=clock; }
    public Page read(Principal principal, UUID id, ProblemHistoryQuery query) {
        var current = incidents.get(principal,id); var visibility = incidents.visibility(principal);
        if (query.asOf().isAfter(clock.instant())) throw new IncidentFailure(INVALID_REQUEST);
        if (current.incident().version() != query.incidentVersion()) throw new IncidentFailure(CONFLICT);
        var entries = reader.observations(principal.tenantId(),id,visibility,query);
        if (entries.size() > query.limit()+1) throw new IncidentFailure(UNAVAILABLE);
        String previous = query.after() == null ? "" : query.after().toString();
        for (var entry : entries) {
            var p=entry.observation();
            if (!p.tenantId().equals(principal.tenantId()) || !query.includes(entry) || entry.id().toString().compareTo(previous)<=0
                    || !entry.visibleTo(visibility.allEntities(),visibility.entities())
                    || current.problems().stream().noneMatch(problem -> problem.observation().sourceInstanceId().equals(p.sourceInstanceId()) && problem.observation().problemEventId().equals(p.problemEventId()))) throw new IncidentFailure(UNAVAILABLE);
            previous=entry.id().toString();
        }
        var items=entries.stream().limit(query.limit()).toList(); return new Page(items,entries.size()>query.limit()?items.getLast().id():null);
    }
    public record Page(List<ProblemObservation> items, UUID nextCursor) {}
}
