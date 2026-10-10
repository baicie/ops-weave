package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit, bounded and authorized backfill of model pins on legacy entity rows. The command is
 * the only writer of these pins: rows are pinned only when their stored attributes verifiably
 * match the named published model, indeterminable rows keep no pin and are reported as counts,
 * and source-managed or already pinned rows are never touched.
 */
public interface EntityModelPinBackfillStore {
    /** Verifies one legacy row against the named published model; never guesses. */
    interface Conformance { boolean accepts(Map<String, Object> attributes); }

    record Counts(int scanned, int updated, int keptWithoutPin, int skippedPinned, int skippedSource) {
        public Counts {
            if (scanned < 0 || scanned > 500 || updated < 0 || updated > 500 || keptWithoutPin < 0 || keptWithoutPin > 500
                || skippedPinned < 0 || skippedSource < 0 || updated + keptWithoutPin != scanned) {
                throw new IllegalArgumentException("Invalid backfill counts");
            }
        }
    }

    record Receipt(UUID requestId, String entityType, EntityModelPin model, Counts counts, Instant completedAt, boolean replayed) {}

    /** Idempotent by requestId: identical content replays the stored receipt, changed content conflicts. */
    Receipt backfill(TenantId tenant, UUID requestId, String entityType, EntityModelPin model, int maxUpdates, Conformance conformance, Instant now);

    Optional<Receipt> receipt(TenantId tenant, UUID requestId);

    final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    final class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
}
