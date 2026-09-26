package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A source-scoped, time-bounded write capability issued by the inventory store, never by a request/model. */
public final class SourceScan {
    private SourceScan() {}
    public static final Duration LEASE = Duration.ofSeconds(30);
    public static final Duration MAX_RUN = Duration.ofMinutes(5);
    private static final long MAX_FENCE = 9_007_199_254_740_991L;

    public record Scope(TenantId tenantId, String sourceInstanceId, String externalType) {
        public Scope {
            Objects.requireNonNull(tenantId);
            if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9_.:-]{1,128}")
                || externalType == null || !externalType.matches("[a-z][a-z0-9-]{0,63}"))
                throw new IllegalArgumentException("Invalid source scan scope");
        }
        public void require(ExternalObjectKey key) {
            if (!tenantId.equals(key.tenantId()) || !sourceInstanceId.equals(key.sourceInstanceId())
                || !externalType.equals(key.externalType())) throw new Failure(Code.LOST);
        }
    }
    public record Token(Scope scope, UUID runId, long fence, Instant startedAt, Instant deadlineAt) {
        public Token {
            Objects.requireNonNull(scope); Objects.requireNonNull(runId);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(deadlineAt);
            if (fence < 1 || fence > MAX_FENCE || startedAt.isBefore(Instant.EPOCH)
                || !deadlineAt.equals(startedAt.plus(MAX_RUN))) throw new IllegalArgumentException("Invalid source scan token");
        }
    }
    public record Lease(Token token, Instant leaseUntil, boolean released) {
        public Lease {
            Objects.requireNonNull(token); Objects.requireNonNull(leaseUntil);
            if (!leaseUntil.isAfter(token.startedAt()) || leaseUntil.isAfter(token.deadlineAt()))
                throw new IllegalArgumentException("Invalid source scan lease");
        }
        public static Lease acquire(Scope scope, UUID runId, Lease old, Instant now) {
            if (old != null) {
                if (!old.token.scope().equals(scope)) throw new Failure(Code.LOST);
                if (!old.released && now.isBefore(old.leaseUntil)) throw new Failure(Code.BUSY);
                if (old.token.runId().equals(runId)) throw new Failure(Code.LOST);
                if (old.token.fence() == MAX_FENCE) throw new Failure(Code.LIMIT);
            }
            var token = new Token(scope, runId, old == null ? 1 : old.token.fence() + 1, now, now.plus(MAX_RUN));
            return new Lease(token, now.plus(LEASE), false);
        }
        public void require(Token presented, Instant now) {
            if (!token.equals(presented) || released || now.isBefore(token.startedAt())) throw new Failure(Code.LOST);
            if (!now.isBefore(token.deadlineAt())) throw new Failure(Code.DEADLINE);
            if (!now.isBefore(leaseUntil)) throw new Failure(Code.LOST);
        }
        public Lease renew(Token presented, Instant now) {
            require(presented, now);
            var next = now.plus(LEASE);
            return new Lease(token, next.isAfter(token.deadlineAt()) ? token.deadlineAt() : next, false);
        }
        public Lease release() { return new Lease(token, leaseUntil, true); }
    }
    public enum Code { BUSY, LOST, DEADLINE, LIMIT }
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
}
