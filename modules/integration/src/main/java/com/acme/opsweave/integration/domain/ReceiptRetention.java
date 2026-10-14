package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Receipt retention: a published per-scope budget plus an explicit preview → confirm cleanup.
 *
 * <p>This deliberately imitates the scan-run budget shape (a bounded scope, a reported ceiling) while
 * changing the parts that would be wrong for receipts: <em>nothing</em> is swept when a scan opens,
 * reading never cleans, protected rows are never deleted and there is no scheduled deletion at all.
 * Deleting a receipt removes the idempotency answer of an old request, so cleanup is always a
 * reasoned, human-confirmed command whose receipt keeps the counts.
 */
public final class ReceiptRetention {
    private ReceiptRetention() {}

    /** The receipt kinds this slice governs. Each has its own natural scope and its own protection rule. */
    public enum Kind {
        /** Private immutable onboarding receipts (`integration.source_setup`), scoped per owner. */
        ONBOARDING("onboarding"),
        /** Source confirmation/command receipts (`integration.source_command`), scoped per source instance. */
        SOURCE_COMMAND("source-command"),
        /** External link registration receipts (`inventory.external_link_registration`), scoped per namespace. */
        EXTERNAL_LINK("external-link");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }

        public static Kind of(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Invalid receipt kind");
            }
        }
    }

    /** Mirrors the scan-run budget defaults: a bounded scope and a bounded tenant total. */
    public record Policy(int maxPerScope, int maxPerTenant) {
        public static final int DEFAULT_MAX_PER_SCOPE = 1000;
        public static final int DEFAULT_MAX_PER_TENANT = 5000;

        public Policy {
            if (maxPerScope < 1 || maxPerScope > DEFAULT_MAX_PER_SCOPE
                || maxPerTenant < maxPerScope || maxPerTenant > DEFAULT_MAX_PER_TENANT) {
                throw new IllegalArgumentException("Invalid receipt retention budget");
            }
        }

        public static Policy defaults() { return new Policy(DEFAULT_MAX_PER_SCOPE, DEFAULT_MAX_PER_TENANT); }

        public static Policy of(Integer maxPerScope, Integer maxPerTenant) {
            var defaults = defaults();
            return new Policy(maxPerScope == null ? defaults.maxPerScope() : maxPerScope,
                maxPerTenant == null ? defaults.maxPerTenant() : maxPerTenant);
        }
    }

    /** How full one scope is. The budget reports; it never deletes by itself. */
    public enum Status {
        OK("There is room for more receipts."),
        NEAR_LIMIT("Most of the receipt budget is used; plan a cleanup before writes start failing."),
        AT_LIMIT("The receipt budget is used up for this scope; run the explicit cleanup.");

        private final String summary;

        Status(String summary) { this.summary = summary; }

        public String summary() { return summary; }
    }

    /** The published fraction of the budget at which the view starts warning. */
    public static final int NEAR_LIMIT_PERCENT = 80;

    public static Status statusOf(int cleanable, int max) {
        if (cleanable < 0 || max < 1 || cleanable > max * 4) throw new IllegalArgumentException("Invalid receipt retention count");
        if (cleanable >= max) return Status.AT_LIMIT;
        return (cleanable * 100L) >= ((long) max * NEAR_LIMIT_PERCENT) ? Status.NEAR_LIMIT : Status.OK;
    }

    /** One stored receipt as the retention view sees it. Protected rows are never cleanup candidates. */
    public record Row(String id, Instant createdAt, boolean protectedRow) {
        public Row {
            if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("Invalid receipt row id");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** What one scope reports. `kept` counts only rows retention may delete. */
    public record Report(Kind kind, String scopeKey, int kept, int protectedRows, int maxPerScope, int maxPerTenant) {
        public Report {
            Objects.requireNonNull(kind, "kind");
            checkScopeKey(scopeKey);
            if (kept < 0 || protectedRows < 0 || kept > maxPerScope * 4) throw new IllegalArgumentException("Invalid receipt retention report");
        }

        public Status status() { return statusOf(kept, maxPerScope); }

        public String summary() { return status().summary(); }
    }

    /**
     * The read-only answer of a cleanup preview. It never changes anything and it keeps both sides of
     * the truth: how many rows would go and how many are retained because they are protected.
     */
    public record Preview(Kind kind, String scopeKey, String previewDigest, List<String> candidates,
                          int removable, int keptProtected, int keptUnfinished, int maxRemove) {
        public Preview {
            Objects.requireNonNull(kind, "kind");
            checkScopeKey(scopeKey);
            WorkflowDefinition.checkDigest(previewDigest);
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty() && removable != 0) throw new IllegalArgumentException("Invalid receipt cleanup preview");
            if (candidates.size() != removable || removable > maxRemove) throw new IllegalArgumentException("Receipt cleanup preview overflow");
            if (keptProtected < 0 || keptUnfinished < 0 || maxRemove < 1 || maxRemove > 100) {
                throw new IllegalArgumentException("Invalid receipt cleanup preview counts");
            }
        }

        public static String digest(Kind kind, String scopeKey, List<String> candidates) {
            var parts = new java.util.ArrayList<String>();
            parts.add("receipt-retention-preview-v1");
            parts.add(kind.name());
            parts.add(scopeKey);
            for (var id : candidates) parts.add(id);
            return WorkflowDefinition.hash(parts);
        }
    }

    /**
     * The confirming command. The preview digest pins exactly what is being removed so a stale or
     * different preview is a conflict instead of a silent widening of the deletion.
     */
    public record Command(UUID requestId, Kind kind, String scopeKey, String previewDigest, String reason, int maxRemove) {
        public Command {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            checkScopeKey(scopeKey);
            WorkflowDefinition.checkDigest(previewDigest);
            if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid receipt cleanup reason");
            }
            if (maxRemove < 1 || maxRemove > 100) throw new IllegalArgumentException("Invalid receipt cleanup limit");
        }

        public String commandDigest() {
            return WorkflowDefinition.hash(List.of("receipt-retention-command-v1", kind.name(), scopeKey,
                previewDigest, reason.trim(), Integer.toString(maxRemove)));
        }
    }

    /** The immutable acknowledgement of one admitted cleanup; the counts are the audit record. */
    public record Receipt(UUID requestId, String commandDigest, Instant createdAt, Kind kind, String scopeKey,
                          int removed, int keptProtected, int keptUnfinished, String reason) {
        public Receipt {
            Objects.requireNonNull(requestId, "requestId");
            WorkflowDefinition.checkDigest(commandDigest);
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(kind, "kind");
            checkScopeKey(scopeKey);
            if (removed < 0 || keptProtected < 0 || keptUnfinished < 0) throw new IllegalArgumentException("Invalid receipt cleanup counts");
            if (reason == null || reason.isBlank() || reason.length() > 256) throw new IllegalArgumentException("Invalid receipt cleanup reason");
        }

        public void require(Command command) {
            if (!requestId.equals(command.requestId()) || kind != command.kind() || !scopeKey.equals(command.scopeKey())
                || !commandDigest.equals(command.commandDigest()) || !reason.equals(command.reason().trim())) {
                throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            }
        }
    }

    /**
     * Protection is per kind and never guessed:
     * an onboarding receipt backs a live source instance, a command receipt is the current authority of
     * its source instance and a registration is the current authority of its external key. Everything
     * older is a superseded receipt; anything else is retained and reported, not deleted.
     */
    public static final Set<String> PROTECTED_KINDS = Set.of("onboarding", "source-command", "external-link");

    public static void checkScopeKey(String scopeKey) {
        if (scopeKey == null || !scopeKey.matches("[A-Za-z0-9_.:@-]{1,128}")) {
            throw new IllegalArgumentException("Invalid receipt retention scope");
        }
    }

    /** Reads are bounded: a preview never walks the whole table and never mutates it. */
    public static final int MAX_SCAN_ROWS = 1000;
}
