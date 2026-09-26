package com.acme.opsweave.inventory.domain;

/**
 * How full one source's stored receipts are. Snapshot and correction receipts are kept forever up to
 * a fixed cap, and the platform <em>refuses</em> a write once the cap is reached
 * ({@code SNAPSHOT_LIMIT}/{@code CORRECTION_LIMIT}) rather than silently dropping history. That makes
 * the cap a planned operational ceiling, so it has to be visible <em>before</em> it is hit: this view
 * reports the current count against the published cap without changing anything.
 *
 * <p>Reading it never prunes, never retires a receipt and never authorizes a write.
 */
public record SourceReceiptCapacity(Kind kind, String sourceInstanceId, int kept, int max) {
    /** The two independent caps a source can hit; they are counted separately. */
    public enum Kind {
        SNAPSHOT("snapshot", "The source reached its stored snapshot receipts; further imports are refused."),
        BINDING_CORRECTION("binding-correction", "The source reached its stored correction receipts; further corrections are refused.");

        private final String label;
        private final String atLimitSummary;

        Kind(String label, String atLimitSummary) {
            this.label = label;
            this.atLimitSummary = atLimitSummary;
        }

        public String label() {
            return label;
        }

        public String atLimitSummary() {
            return atLimitSummary;
        }
    }

    /** How close a source is to its cap. */
    public enum Status {
        OK("There is room for more receipts."),
        NEAR_LIMIT("Most of the receipt budget is used; plan a review before imports start failing."),
        AT_LIMIT("The cap is reached: writes for this kind are refused until receipts are removed.");

        private final String summary;

        Status(String summary) {
            this.summary = summary;
        }

        public String summary() {
            return summary;
        }
    }

    /** The fraction of the cap at which the view starts warning. */
    public static final int NEAR_LIMIT_PERCENT = 80;

    public SourceReceiptCapacity {
        java.util.Objects.requireNonNull(kind, "kind");
        if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid capacity source");
        }
        if (max < 1 || kept < 0 || kept > max) {
            throw new IllegalArgumentException("Invalid receipt capacity");
        }
    }

    public Status status() {
        if (kept >= max) {
            return Status.AT_LIMIT;
        }
        return (kept * 100L) >= ((long) max * NEAR_LIMIT_PERCENT) ? Status.NEAR_LIMIT : Status.OK;
    }

    /** What the operator can do about it, in fixed wording. */
    public String summary() {
        return status() == Status.AT_LIMIT ? kind.atLimitSummary() : status().summary();
    }

    /** The published cap for one kind, so the view and the write path cannot drift apart. */
    public static int cap(Kind kind) {
        return switch (kind) {
            case SNAPSHOT -> SourceSnapshot.MAX_RECEIPTS;
            case BINDING_CORRECTION -> SourceBindingCorrection.MAX_RECEIPTS;
        };
    }
}
