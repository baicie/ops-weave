package com.acme.opsweave.alerting.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Immutable normalized source input, captured before occurrence projection merges recovery knowledge. */
public record ProblemObservation(UUID id, ExternalProblem observation, String dataMode,
        Instant firstReceivedAt, Map<String,EntityId> entities) {
    public static final String SOURCE_CONTRACT = "zabbix-7.0-event-v1";
    public ProblemObservation {
        Objects.requireNonNull(observation); Objects.requireNonNull(firstReceivedAt);
        if (!idOf(observation).equals(id) || observation.observedAt().isBefore(Instant.EPOCH)
                || firstReceivedAt.isBefore(observation.observedAt()) || !Set.of("labeled-fixture","zabbix-jsonrpc").contains(dataMode)) throw new IllegalArgumentException("Invalid problem observation metadata");
        entities = Map.copyOf(entities);
        if (!observation.hostIds().containsAll(entities.keySet())) throw new IllegalArgumentException("Invalid observed entity mapping");
    }
    public static UUID idOf(ExternalProblem problem) {
        String identity = "problem-observation-v1\0" + problem.tenantId().value() + '\0' + problem.sourceInstanceId() + '\0' + problem.problemEventId() + '\0' + problem.observedAt();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
    public static ProblemObservation capture(ExternalProblem problem, String mode, Instant receivedAt, Map<String,EntityId> entities) {
        return new ProblemObservation(idOf(problem),problem,mode,receivedAt,entities);
    }
    /** Re-delivery can resolve new entity links in the live projection but never rewrite this first capture. */
    public boolean sameInput(ProblemObservation other) { return id.equals(other.id) && observation.equals(other.observation) && dataMode.equals(other.dataMode); }
    public boolean hasUnmappedHosts() { return observation.hostIds().isEmpty() || !entities.keySet().containsAll(observation.hostIds()); }
    public boolean visibleTo(boolean allEntities, Set<EntityId> allowed) { return allEntities || (!hasUnmappedHosts() && allowed.containsAll(entities.values())); }
}
