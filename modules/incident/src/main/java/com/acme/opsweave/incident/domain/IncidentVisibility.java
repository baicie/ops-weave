package com.acme.opsweave.incident.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.util.*;

public record IncidentVisibility(boolean allIncidents, Set<UUID> incidents, boolean allEntities, Set<EntityId> entities) {
    public IncidentVisibility {
        incidents = Set.copyOf(incidents); entities = Set.copyOf(entities);
        if (incidents.size() > 1000 || entities.size() > 1000 || (allIncidents && !incidents.isEmpty()) || (allEntities && !entities.isEmpty())) throw new IllegalArgumentException("Invalid incident scope");
    }
    public boolean includes(IncidentRecord record) {
        return (allIncidents || incidents.contains(record.incident().id())) && (allEntities || (!record.hasUnmappedHosts()
            && !record.entityIds().isEmpty() && entities.containsAll(record.entityIds())));
    }
}
