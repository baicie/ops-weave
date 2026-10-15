package com.acme.opsweave.aicontrol.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Runtime's state attestation as the platform receives it (M5 5d, ADR-104 §2.2/§3).
 *
 * <p>One {@link Report} is one at-least-once delivery of a closed projection of the Runtime's own
 * internal progress: the attempt it observed, its internal status and three counters. It is a
 * receipt projection, never the Runtime's StepRun/Checkpoint internals — those stay Runtime-owned,
 * and the platform never stores them and never advances a run because a report arrived.
 *
 * <p>Delivery is idempotent on the request id: a replay answers with the original {@link Receipt},
 * the same request id with different content is an explicit {@link AiRun.Conflict}, and the request
 * id shares the one idempotency namespace of submissions and control commands. Exactly-once is
 * never claimed; a retry may deliver the same report twice and this object absorbs the duplicate.
 */
public final class AiRunRuntimeState {
    private AiRunRuntimeState() {}

    /** The server constant bounding stored attestations per run; older receipts are dropped. */
    public static final int RETAINED_REPORTS = 50;

    /** A closed attestation: counters and status only. No step names, checkpoints or evidence. */
    public record Report(UUID requestId, UUID runId, int attempt, String status, int stepCount,
                         long checkpointSeq, int evidenceRecheckCount, Instant reportedAt) {
        public Report {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(reportedAt, "reportedAt");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (attempt < 1 || attempt > 1_000_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (stepCount < 0 || stepCount > 1_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (checkpointSeq < 0 || checkpointSeq > 1_000_000_000L) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (evidenceRecheckCount < 0 || evidenceRecheckCount > 1_000) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /** A replay must name this exact report; anything else is an explicit conflict. */
        public void require(Report other) {
            if (!equals(other)) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        }
    }

    /** The idempotent delivery receipt: the stored attestation echoed back to the deliverer. */
    public record Receipt(UUID requestId, UUID runId, int attempt, String status, Instant recordedAt) {
        public Receipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (attempt < 0 || attempt > 1_000_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /**
         * The receipt of one stored attestation. It is derived from the stored report alone, so a
         * replay answers with a byte-identical receipt instead of a fresh timestamp.
         */
        public static Receipt of(Report report) {
            Objects.requireNonNull(report, "report");
            return new Receipt(report.requestId(), report.runId(), report.attempt(), report.status(),
                report.reportedAt());
        }
    }

    /**
     * The bounded read-only aggregate of one run (5d): event retention with its truncation
     * counters, the model fee counters and the delivered Runtime state reports. It is a projection
     * over stored facts — reading it executes nothing and nothing runs in the background because it
     * was read.
     */
    public record Observability(UUID runId, String status, long statusVersion, int attempt,
                                Events events, Spend spend, Runtime runtime) {
        public Observability {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(spend, "spend");
            Objects.requireNonNull(runtime, "runtime");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (statusVersion < 0 || attempt < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** The event retention window of this run: kept records, truncated records, truncation flag. */
        public record Events(int window, int retained, long dropped, boolean truncated) {
            public Events {
                if (window < 1 || window > 10_000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                if (retained < 0 || retained > window) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                if (dropped < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                // Truncation is a fact about the window: the flag and the counter say the same thing.
                if (truncated != (dropped > 0)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /** The model fee counters of this run: what was spent and what is still uncertain. */
        public record Spend(int priorModelCalls, int uncertainPriorFees) {
            public Spend {
                if (priorModelCalls < 0 || priorModelCalls > 8) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                if (uncertainPriorFees < 0 || uncertainPriorFees > 8) {
                    throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                }
                if (uncertainPriorFees > priorModelCalls) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /** The delivered Runtime state reports: kept, truncated and the newest attestation. */
        public record Runtime(int deliveredReports, long droppedReports, Integer latestAttempt, Instant lastReportedAt) {
            public Runtime {
                if (deliveredReports < 0 || deliveredReports > 1_000_000) {
                    throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                }
                if (droppedReports < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                if (latestAttempt != null && (latestAttempt < 1 || latestAttempt > 1_000_000)) {
                    throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                }
                if ((latestAttempt == null) != (lastReportedAt == null)) {
                    throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                }
                if ((deliveredReports == 0) != (latestAttempt == null)) {
                    throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                }
            }
        }
    }

    /** A closed run status name; anything outside the run state machine is rejected. */
    private static boolean statusName(String value) {
        for (var status : AiRun.Status.values()) if (status.name().equals(value)) return true;
        return false;
    }
}
