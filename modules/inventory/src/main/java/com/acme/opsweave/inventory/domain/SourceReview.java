package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

/** Human review of a bounded supplemental record. No identity or lifecycle inference. */
public record SourceReview(UUID id, TenantId tenantId, EntityId entityId, long baseVersion,
        ExternalObjectKey source, Instant observedAt, Instant ingestedAt, Map<String,String> values,
        Map<String,String> primaryAtImport, String mappingDigest, String actor, List<Decision> decisions, AssetIdentity.Pin identity) {
    public SourceReview(UUID id, TenantId tenantId, EntityId entityId, long baseVersion, ExternalObjectKey source, Instant observedAt, Instant ingestedAt,
            Map<String,String> values, Map<String,String> primaryAtImport, String mappingDigest, String actor, List<Decision> decisions) {
        this(id,tenantId,entityId,baseVersion,source,observedAt,ingestedAt,values,primaryAtImport,mappingDigest,actor,decisions,null);
    }
    public static final Set<String> FIELDS = Set.of("name", "ip", "owner", "environment");
    public static final Duration MAX_AGE = Duration.ofDays(7);
    public enum Action { ACCEPT, REJECT, REVOKE }
    public enum Choice { PRIMARY, SUPPLEMENTAL }
    public enum Status { PENDING, ACCEPTED, REJECTED, REVOKED }
    public SourceReview {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(entityId);
        Objects.requireNonNull(source); Objects.requireNonNull(observedAt); Objects.requireNonNull(ingestedAt);
        bounded(source.externalId(), 256);
        if (!source.sourceInstanceId().matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid import source");
        if (!source.tenantId().equals(tenantId) || !"cmdb-host".equals(source.externalType()) || !"1".equals(source.generation())
                || baseVersion < 1 || observedAt.isBefore(Instant.EPOCH) || observedAt.isAfter(ingestedAt)) throw new IllegalArgumentException("Invalid source review");
        values = fields(values, false); primaryAtImport = fields(primaryAtImport, true);
        if (mappingDigest == null || !mappingDigest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid mapping digest");
        actor = bounded(actor, 128); decisions = List.copyOf(decisions);
        if (decisions.size() > 2) throw new IllegalArgumentException("Invalid review history");
        for (int i = 0; i < decisions.size(); i++) {
            var d = decisions.get(i);
            if ((i == 0 && d.action() == Action.REVOKE) || (i == 1 && (decisions.getFirst().action() != Action.ACCEPT || d.action() != Action.REVOKE))
                    || d.at().isBefore(ingestedAt) || (i > 0 && d.at().isBefore(decisions.get(i - 1).at()))) throw new IllegalArgumentException("Invalid review transition");
            if (d.action() == Action.ACCEPT && !d.choices().keySet().equals(values.keySet())) throw new IllegalArgumentException("Every imported field requires a choice");
        }
    }
    public static Map<String,String> fields(Map<String,String> input, boolean emptyAllowed) {
        Objects.requireNonNull(input);
        if ((!emptyAllowed && input.isEmpty()) || !FIELDS.containsAll(input.keySet())) throw new IllegalArgumentException("Unsupported import fields");
        var result = new TreeMap<String,String>();
        input.forEach((key, value) -> result.put(key, bounded(value, key.equals("ip") ? 128 : 255)));
        return Map.copyOf(result);
    }
    public static String bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid review text");
        return value;
    }
    public Status status() { return decisions.isEmpty() ? Status.PENDING : Status.valueOf(decisions.getLast().action() == Action.ACCEPT ? "ACCEPTED" : decisions.getLast().action() == Action.REJECT ? "REJECTED" : "REVOKED"); }
    public int version() { return decisions.size() + 1; }
    public Instant expiresAt() { return observedAt.plus(MAX_AGE); }
    public boolean stale(Instant now) { return !expiresAt().isAfter(now); }
    public SourceReview decide(Command command, long entityVersion, Instant now) {
        if (command.expectedReviewVersion() != version() || command.expectedEntityVersion() != entityVersion) throw new Conflict("Review or entity version changed");
        if (command.action() == Action.REVOKE ? status() != Status.ACCEPTED : status() != Status.PENDING) throw new Conflict("Review is not in the required state");
        if (command.action() == Action.ACCEPT && (entityVersion != baseVersion || stale(now))) throw new Conflict("Import is stale or entity changed; create a new review");
        var history = new ArrayList<>(decisions);
        history.add(new Decision(command.requestId(), command.action(), command.choices(), command.reason(), command.actor(), now, entityVersion + (command.action() == Action.REJECT ? 0 : 1)));
        return new SourceReview(id, tenantId, entityId, baseVersion, source, observedAt, ingestedAt, values, primaryAtImport, mappingDigest, actor, history, identity);
    }
    public record Command(UUID requestId, Action action, long expectedEntityVersion, int expectedReviewVersion,
            Map<String,Choice> choices, String reason, String actor) {
        public Command {
            Objects.requireNonNull(requestId); Objects.requireNonNull(action); choices = Map.copyOf(choices);
            if (expectedEntityVersion < 1 || expectedReviewVersion < 1 || expectedReviewVersion > 2 || !FIELDS.containsAll(choices.keySet())
                    || (action != Action.ACCEPT && !choices.isEmpty())) throw new IllegalArgumentException("Invalid review decision");
            reason = bounded(reason, 500); actor = bounded(actor, 128);
        }
    }
    public record Decision(UUID requestId, Action action, Map<String,Choice> choices, String reason, String actor, Instant at, long entityVersion) {
        public Decision {
            new Command(requestId, action, entityVersion, 1, choices, reason, actor);
            choices = Map.copyOf(choices); Objects.requireNonNull(at);
        }
    }
    public static final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
}
