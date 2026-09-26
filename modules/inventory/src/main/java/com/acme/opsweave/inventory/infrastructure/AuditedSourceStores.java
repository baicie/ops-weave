package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.RejectedWriteAttemptStore;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.api.SourceSnapshotStore;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.inventory.domain.SourceBindingCorrection;
import com.acme.opsweave.inventory.domain.SourceReview;
import com.acme.opsweave.inventory.domain.SourceSnapshot;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records refused supplemental-source writes. The refused request is rethrown unchanged: auditing is
 * evidence about the refusal, not a second gate and not a hidden retry. Only the fixed reason code,
 * the refused operation and the attempted field names are stored — never a value or a message.
 */
public final class AuditedSourceStores {
    private final RejectedWriteAttemptStore audit;
    private final Clock clock;
    private final String configuredSource;

    private AuditedSourceStores(RejectedWriteAttemptStore audit, Clock clock, String configuredSource) {
        this.audit = audit;
        this.clock = clock;
        this.configuredSource = configuredSource;
    }

    public static SourceReviewStore reviews(
        SourceReviewStore delegate,
        RejectedWriteAttemptStore audit,
        Clock clock,
        String configuredSource
    ) {
        return new AuditedSourceStores(audit, clock, configuredSource).new Review(delegate);
    }

    public static SourceSnapshotStore snapshots(
        SourceSnapshotStore delegate,
        RejectedWriteAttemptStore audit,
        Clock clock,
        String configuredSource
    ) {
        return new AuditedSourceStores(audit, clock, configuredSource).new Snapshot(delegate);
    }

    private void record(
        TenantId tenant,
        RejectedWriteAttempt.Method method,
        RejectedWriteAttempt.Code code,
        String source,
        String actor,
        Iterable<String> candidateFields
    ) {
        // The audit is only readable for the configured import source, so nothing else is written:
        // a row nobody can read back would be worse than no row.
        if (source == null || !source.equals(configuredSource)) {
            return;
        }
        audit.record(new RejectedWriteAttempt(
            UUID.randomUUID(),
            tenant,
            source,
            method == RejectedWriteAttempt.Method.CORRECT_BINDING
                ? RejectedWriteAttempt.Kind.BINDING_CORRECTION
                : RejectedWriteAttempt.Kind.FIELD_REVIEW,
            method,
            code,
            actor(actor),
            fields(candidateFields),
            clock.instant()
        ));
    }

    /** A refusal is recorded even when the caller did not present an actor the platform will echo. */
    private static String actor(String actor) {
        return actor == null || actor.isBlank() || actor.length() > 128 || actor.trim().length() != actor.length()
            ? "unknown" : actor;
    }

    private static RejectedWriteAttempt.Code reviewCode(SourceReview.Conflict conflict) {
        String message = conflict.getMessage() == null ? "" : conflict.getMessage();
        if (message.startsWith("Entity version changed")) {
            return RejectedWriteAttempt.Code.ENTITY_VERSION_CHANGED;
        }
        if (message.startsWith("Review is not in the required state")) {
            return RejectedWriteAttempt.Code.REVIEW_STATE_CHANGED;
        }
        if (message.startsWith("Import is stale")) {
            return RejectedWriteAttempt.Code.REVIEW_STALE;
        }
        return RejectedWriteAttempt.Code.REVIEW_VERSION_CHANGED;
    }

    private static RejectedWriteAttempt.Code snapshotCode(SourceSnapshot.Code code) {
        return switch (code) {
            case BINDING_CONFLICT -> RejectedWriteAttempt.Code.BINDING_CONFLICT;
            case SNAPSHOT_OUTDATED -> RejectedWriteAttempt.Code.SNAPSHOT_OUTDATED;
            case REQUEST_CONFLICT -> RejectedWriteAttempt.Code.REQUEST_CONFLICT;
            case SNAPSHOT_LIMIT -> RejectedWriteAttempt.Code.LIMIT_REACHED;
            case IDENTITY_UNRESOLVED -> RejectedWriteAttempt.Code.IDENTITY_UNRESOLVED;
        };
    }

    private static RejectedWriteAttempt.Code correctionCode(SourceBindingCorrection.Code code) {
        return switch (code) {
            case BINDING_CHANGED -> RejectedWriteAttempt.Code.BINDING_CHANGED;
            case BINDING_UNCHANGED -> RejectedWriteAttempt.Code.BINDING_UNCHANGED;
            case BINDING_FIELDS_ACTIVE -> RejectedWriteAttempt.Code.BINDING_FIELDS_ACTIVE;
            case CORRECTION_REQUEST_CONFLICT -> RejectedWriteAttempt.Code.REQUEST_CONFLICT;
            case CORRECTION_LIMIT -> RejectedWriteAttempt.Code.LIMIT_REACHED;
        };
    }

    /**
     * Allow-listed field names only, in a stable order; anything the domain does not recognize is
     * dropped rather than copied, and the caller's map order never decides what the audit reports.
     */
    private static List<String> fields(Iterable<String> candidate) {
        java.util.TreeSet<String> accepted = new java.util.TreeSet<>();
        for (String name : candidate) {
            if (SourceReview.FIELDS.contains(name)) {
                accepted.add(name);
            }
            if (accepted.size() == RejectedWriteAttempt.MAX_FIELDS) {
                break;
            }
        }
        return List.copyOf(accepted);
    }

    /** The attempted field names, or the whole field set when the request carried no values. */
    private static Iterable<String> names(Map<String, ?> values, Set<String> fallback) {
        return values.isEmpty() ? fallback : values.keySet();
    }

    /** Field names across the rows of a refused snapshot, allow-listed in {@link #fields}. */
    private static Iterable<String> values(List<SourceSnapshot.Row> records) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (SourceSnapshot.Row row : records) {
            names.addAll(row.values().keySet());
        }
        return names.isEmpty() ? SourceReview.FIELDS : names;
    }

    private final class Review implements SourceReviewStore {
        private final SourceReviewStore delegate;

        private Review(SourceReviewStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public SourceReview stage(TenantId tenant, EntityId entity, Import input, Instant now) {
            try {
                return delegate.stage(tenant, entity, input, now);
            } catch (SourceReview.Conflict refused) {
                record(tenant, RejectedWriteAttempt.Method.STAGE_REVIEW, reviewCode(refused),
                    input.source().sourceInstanceId(), input.actor(), names(input.values(), SourceReview.FIELDS));
                throw refused;
            }
        }

        @Override
        public SourceReview decide(TenantId tenant, EntityId entity, String sourceId, UUID reviewId, SourceReview.Command command, Instant now) {
            try {
                return delegate.decide(tenant, entity, sourceId, reviewId, command, now);
            } catch (SourceReview.Conflict refused) {
                record(tenant, RejectedWriteAttempt.Method.DECIDE_REVIEW, reviewCode(refused),
                    sourceId, command.actor(), names(command.choices(), SourceReview.FIELDS));
                throw refused;
            }
        }

        @Override
        public Page reviews(TenantId tenant, EntityId entity, String sourceId, UUID after, int limit) {
            return delegate.reviews(tenant, entity, sourceId, after, limit);
        }
    }

    private final class Snapshot implements SourceSnapshotStore {
        private final SourceSnapshotStore delegate;

        private Snapshot(SourceSnapshotStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public SourceSnapshot.Receipt ingest(TenantId tenant, String actor, String source, String namespace, SourceSnapshot.Input input, String mappingDigest) {
            try {
                return delegate.ingest(tenant, actor, source, namespace, input, mappingDigest);
            } catch (SourceSnapshot.Conflict refused) {
                record(tenant, RejectedWriteAttempt.Method.INGEST_SNAPSHOT, snapshotCode(refused.code()),
                    source, actor, values(input.records()));
                throw refused;
            }
        }

        @Override
        public Optional<SourceSnapshot.Receipt> receipt(TenantId tenant, String actor, String source, String namespace, UUID requestId) {
            return delegate.receipt(tenant, actor, source, namespace, requestId);
        }

        @Override
        public Page presence(TenantId tenant, EntityId entity, String source) {
            return delegate.presence(tenant, entity, source);
        }

        @Override
        public SourceBindingCorrection.Receipt correct(TenantId tenant, String actor, String source, String namespace, SourceBindingCorrection.Command command, String mappingDigest) {
            try {
                return delegate.correct(tenant, actor, source, namespace, command, mappingDigest);
            } catch (SourceSnapshot.Conflict refused) {
                record(tenant, RejectedWriteAttempt.Method.CORRECT_BINDING, snapshotCode(refused.code()),
                    source, actor, names(command.values(), SourceReview.FIELDS));
                throw refused;
            } catch (SourceBindingCorrection.Conflict refused) {
                record(tenant, RejectedWriteAttempt.Method.CORRECT_BINDING, correctionCode(refused.code()),
                    source, actor, names(command.values(), SourceReview.FIELDS));
                throw refused;
            }
        }

        @Override
        public Optional<SourceBindingCorrection.Receipt> correction(TenantId tenant, String actor, String source, String namespace, UUID requestId) {
            return delegate.correction(tenant, actor, source, namespace, requestId);
        }

        @Override
        public List<SourceBindingCorrection.Receipt> corrections(TenantId tenant, String source, String namespace, EntityId entity, UUID after, int limit) {
            return delegate.corrections(tenant, source, namespace, entity, after, limit);
        }
    }
}
