package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** Operator-verified scoped asset UUID. Names, IPs and upstream self-reported authority are not identity keys. */
public record AssetIdentity(UUID id, TenantId tenantId, EntityId entityId, String namespace, String value,
        String actor, String reason, Instant assertedAt, Revocation revocation) {
    public static final int MAX_ACTIVE = 16, MAX_RECORDS = 1000;
    public AssetIdentity {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(entityId); Objects.requireNonNull(assertedAt);
        namespace = namespace(namespace); value = value(value); actor = SourceReview.bounded(actor,128); reason = SourceReview.bounded(reason,500);
        if (assertedAt.isBefore(Instant.EPOCH) || (revocation != null && revocation.at().isBefore(assertedAt))) throw new IllegalArgumentException("Invalid identity time");
    }
    public static String namespace(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid identity namespace");
        return value;
    }
    public static String value(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) throw new IllegalArgumentException("Expected a canonical scoped asset UUID");
        return value;
    }
    public boolean active() { return revocation == null; }
    public int version() { return active() ? 1 : 2; }
    public Pin pin() { if (!active()) throw new SourceReview.Conflict("Identity is revoked"); return new Pin(id,namespace,value,1); }
    public AssetIdentity revoke(Command command, Instant now) {
        if (!active() || command.action() != Action.REVOKE || !id.equals(command.identityId())) throw new SourceReview.Conflict("Identity changed");
        return new AssetIdentity(id,tenantId,entityId,namespace,value,actor,reason,assertedAt,new Revocation(command.requestId(),command.actor(),command.reason(),now));
    }
    public void requirePin(TenantId tenant, EntityId entity, Pin pin) {
        if (!active() || !tenantId.equals(tenant) || !entityId.equals(entity) || !pin().equals(pin)) throw new SourceReview.Conflict("Resolved identity changed");
    }
    public record Pin(UUID id, String namespace, String value, int version) {
        public Pin { Objects.requireNonNull(id); namespace = AssetIdentity.namespace(namespace); value = AssetIdentity.value(value); if(version != 1) throw new IllegalArgumentException("Invalid identity pin"); }
    }
    public enum Action { ASSERT, REVOKE }
    public record Command(UUID requestId, Action action, UUID identityId, long expectedEntityVersion, String value, String actor, String reason) {
        public Command {
            Objects.requireNonNull(requestId); Objects.requireNonNull(action); Objects.requireNonNull(identityId);
            if (expectedEntityVersion < 1 || expectedEntityVersion >= 9_007_199_254_740_991L) throw new IllegalArgumentException("Invalid entity version");
            if (action == Action.ASSERT) { value = AssetIdentity.value(value); if(!identityId.equals(requestId)) throw new IllegalArgumentException("Assertion identity must equal request id"); }
            else if (value != null) throw new IllegalArgumentException("Revocation does not replace identity");
            actor = SourceReview.bounded(actor,128); reason = SourceReview.bounded(reason,500);
        }
    }
    public record Revocation(UUID requestId, String actor, String reason, Instant at) {
        public Revocation { Objects.requireNonNull(requestId); Objects.requireNonNull(at); actor=SourceReview.bounded(actor,128); reason=SourceReview.bounded(reason,500); }
    }
}
