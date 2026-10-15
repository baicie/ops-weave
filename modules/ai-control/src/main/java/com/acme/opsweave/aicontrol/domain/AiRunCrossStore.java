package com.acme.opsweave.aicontrol.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The cross-store reconciliation of one run (M5 5d, ADR-104 §3): the platform registry against the
 * Runtime's own internal state.
 *
 * <p><b>Reconciliation only discloses.</b> {@link #compare} reads both sides and returns the closed
 * finding list; it never repairs, never rewrites a state, never starts, stops or re-runs anything
 * and never pretends the two stores are one. Divergence is data an operator looks at — recovery
 * stays the 5b retry entry with its own re-authorization and its own fencing.
 *
 * <p>The finding set is closed and shared with the Runtime's own reconciler and with the
 * {@code ai-run-cross-store-report} contract. Terminal-vs-active divergence has its own findings (it
 * is never hidden behind a generic status mismatch); attempt and staleness findings are independent.
 */
public final class AiRunCrossStore {
    private AiRunCrossStore() {}

    /** The constant disposition of every report: disclose only, never repair. */
    public static final String REPAIR = "none";

    /** The closed finding set. Nothing outside this set is a cross-store finding. */
    public enum Finding {
        MISSING_ON_PLATFORM,
        MISSING_RUNTIME_STATE,
        STATUS_MISMATCH,
        PLATFORM_TERMINAL_RUNTIME_ACTIVE,
        RUNTIME_TERMINAL_PLATFORM_ACTIVE,
        ATTEMPT_MISMATCH,
        STALE_RUNTIME_REPORT,
        UNDELIVERED_STATE_REPORT
    }

    /** The platform registry view of the comparison. */
    public record PlatformView(String status, long statusVersion, int attempt, Instant updatedAt) {
        public PlatformView {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (statusVersion < 0 || attempt < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        public boolean terminal() {
            return AiRun.Status.valueOf(status).terminal();
        }

        public static PlatformView of(AiRun.Run run) {
            return new PlatformView(run.status().name(), run.statusVersion(), run.attempt(), run.updatedAt());
        }
    }

    /** The Runtime's side of the comparison: the last delivered attestation, never its internals. */
    public record RuntimeView(String status, int attempt, int stepCount, long checkpointSeq, Instant reportedAt) {
        public RuntimeView {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reportedAt, "reportedAt");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (attempt < 1 || attempt > 1_000_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (stepCount < 0 || stepCount > 1_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (checkpointSeq < 0 || checkpointSeq > 1_000_000_000L) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        public boolean terminal() {
            return AiRun.Status.valueOf(status).terminal();
        }
    }

    /**
     * One report of one comparison. The findings are closed, unique and complete for the two views:
     * the constructor recomputes the state-derived findings and accepts exactly those (plus or minus
     * the undelivered-deliveries finding), so an unsound or invented finding is a construction error
     * instead of a quiet claim.
     */
    public record Report(UUID runId, Instant comparedAt, PlatformView platform, RuntimeView runtime,
                         List<Finding> findings) {
        public Report {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(comparedAt, "comparedAt");
            findings = List.copyOf(findings);
            // A report compares two sides; with neither side present there is nothing to disclose.
            if (platform == null && runtime == null) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            var sorted = new ArrayList<>(findings);
            sorted.sort(null);
            if (!sorted.equals(findings) || new java.util.HashSet<>(findings).size() != findings.size()) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            var mandatory = expected(platform, runtime, 0);
            var optional = expected(platform, runtime, 1);
            if (!findings.equals(mandatory) && !findings.equals(optional)) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        public String repair() {
            return REPAIR;
        }

        public boolean consistent() {
            return findings.isEmpty();
        }
    }

    /**
     * Compare the two stores' views of one run. Pure and total: it reads both sides, returns the
     * closed finding list and mutates neither. A report about a run without any side is rejected
     * instead of inventing an empty comparison.
     */
    public static Report compare(UUID runId, Instant comparedAt, PlatformView platform, RuntimeView runtime,
            long undeliveredReports) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(comparedAt, "comparedAt");
        if (platform == null && runtime == null) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (undeliveredReports < 0 || undeliveredReports > 1_000_000) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        return new Report(runId, comparedAt, platform, runtime, expected(platform, runtime, undeliveredReports));
    }

    /** The closed, complete finding list of one comparison, ordered by declaration. */
    private static List<Finding> expected(PlatformView platform, RuntimeView runtime, long undeliveredReports) {
        var findings = new ArrayList<Finding>();
        if (platform == null) {
            findings.add(Finding.MISSING_ON_PLATFORM);
        } else if (runtime == null) {
            findings.add(Finding.MISSING_RUNTIME_STATE);
        } else {
            if (platform.terminal() && !runtime.terminal()) {
                findings.add(Finding.PLATFORM_TERMINAL_RUNTIME_ACTIVE);
            } else if (!platform.terminal() && runtime.terminal()) {
                findings.add(Finding.RUNTIME_TERMINAL_PLATFORM_ACTIVE);
            } else if (!platform.status().equals(runtime.status())) {
                findings.add(Finding.STATUS_MISMATCH);
            }
            if (platform.attempt() != runtime.attempt()) findings.add(Finding.ATTEMPT_MISMATCH);
            if (runtime.reportedAt().isBefore(platform.updatedAt())) findings.add(Finding.STALE_RUNTIME_REPORT);
        }
        if (undeliveredReports > 0) findings.add(Finding.UNDELIVERED_STATE_REPORT);
        return List.copyOf(findings);
    }

    private static boolean statusName(String value) {
        for (var status : AiRun.Status.values()) if (status.name().equals(value)) return true;
        return false;
    }
}
