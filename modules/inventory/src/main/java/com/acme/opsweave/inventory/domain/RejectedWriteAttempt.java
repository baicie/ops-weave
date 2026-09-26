package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Evidence that a write attempt was refused. Decisions already leave a receipt (ADR-027/037); a 409
 * left none, so "who tried to change what, and why it was refused" was unanswerable after the fact.
 *
 * <p>The record is deliberately narrow: a fixed reason code, the refused method, and the names of the
 * fields the caller tried to send. It never stores a field value, a vendor payload or an exception
 * message — a rejection log must not become the place where untrusted source text is kept.
 */
public record RejectedWriteAttempt(
    UUID id,
    TenantId tenantId,
    String sourceInstanceId,
    Kind kind,
    Method method,
    Code code,
    String actor,
    List<String> fieldNames,
    Instant attemptedAt
) {
    /** Both are operations the platform refused to perform. */
    public enum Kind { FIELD_REVIEW, BINDING_CORRECTION }

    /** Allow-listed store operations; a caller cannot make the audit claim anything else. */
    public enum Method {
        STAGE_REVIEW("stage-review"),
        DECIDE_REVIEW("decide-review"),
        CORRECT_BINDING("correct-binding"),
        INGEST_SNAPSHOT("ingest-snapshot");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Stable reasons a refusal can be recorded under. No raw text, no vendor wording. */
    public enum Code {
        ENTITY_VERSION_CHANGED("The entity changed since the request was prepared."),
        REVIEW_VERSION_CHANGED("The review changed since the request was prepared."),
        REVIEW_STATE_CHANGED("The review is not in the state this action requires."),
        REVIEW_STALE("The import is older than the accepted window."),
        BINDING_CHANGED("The source binding changed since the request was prepared."),
        BINDING_UNCHANGED("The correction would not change the binding."),
        BINDING_FIELDS_ACTIVE("Active fields must be revoked before they can be corrected."),
        BINDING_CONFLICT("The target is already bound by another source or namespace."),
        SNAPSHOT_OUTDATED("The observation predates the stored snapshot."),
        REQUEST_CONFLICT("The same request id was already used with different content."),
        LIMIT_REACHED("The scope reached its retention limit for this kind of record."),
        IDENTITY_UNRESOLVED("The registered identity could not be resolved.");

        private final String summary;

        Code(String summary) {
            this.summary = summary;
        }

        /** Fixed wording; the API and logs never echo a stored or vendor-provided message. */
        public String summary() {
            return summary;
        }
    }

    public static final int MAX_FIELDS = 16;

    public RejectedWriteAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid audit source");
        }
        if (actor == null || actor.isBlank() || actor.length() > 128 || actor.trim().length() != actor.length()) {
            throw new IllegalArgumentException("Invalid audit actor");
        }
        fieldNames = List.copyOf(fieldNames);
        if (fieldNames.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Too many audited field names");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String field : fieldNames) {
            if (field == null || !field.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || !seen.add(field)) {
                throw new IllegalArgumentException("Invalid audited field name");
            }
        }
        if (attemptedAt.isBefore(Instant.EPOCH) || attemptedAt.getEpochSecond() > 253402300799L) {
            throw new IllegalArgumentException("Invalid audit time");
        }
    }

    /**
     * Rows kept per tenant and source. A rejection log is operational evidence, not business history:
     * it is bounded exactly like the other append-only receipts and never grows with the deployment.
     */
    public record Policy(int maxPerSource) {
        public static final int DEFAULT_MAX_PER_SOURCE = 500;
        public static final int CEILING = 5000;

        public Policy {
            if (maxPerSource < 1 || maxPerSource > CEILING) {
                throw new IllegalArgumentException("Invalid audit retention policy");
            }
        }

        public static Policy defaults() {
            return new Policy(DEFAULT_MAX_PER_SOURCE);
        }

        /** {@code null} keeps the published default; anything above the ceiling is refused. */
        public static Policy of(Integer maxPerSource) {
            return maxPerSource == null ? defaults() : new Policy(maxPerSource);
        }
    }
}
