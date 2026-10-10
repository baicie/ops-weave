package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transaction boundary for external-link registrations and bounded reads. The store is the only
 *  place a registration may bind a key to an entity; it enforces idempotency and refuses silent
 *  rebinding of an already-bound key. */
public interface ExternalLinkRegistrationStore {
    Result register(ExternalLinkRegistration registration, Instant registeredAt);
    Optional<Entry> find(TenantId tenant, UUID requestId);
    List<Entry> page(TenantId tenant, String namespace, UUID after, int limit);

    record Entry(ExternalLinkRegistration registration, Instant registeredAt) {}
    record Result(Entry entry, boolean replayed) {}

    final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
    final class Access extends RuntimeException {
        public Access(String message) { super(message); }
    }
    final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }
}
