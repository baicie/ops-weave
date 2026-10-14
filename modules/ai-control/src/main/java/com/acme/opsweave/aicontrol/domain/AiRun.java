package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable diagnosis run registry entry (M5, ADR-104).
 *
 * <p>A run records one admitted diagnosis request — what was asked, against which incident and time
 * window, with which pinned Skill — and where it stands in the run state machine. Submitting is
 * idempotent on the request id: a replay returns the same run, the same request id with different
 * content is an explicit conflict, and no second run is ever created.
 *
 * <p>Execution metadata is deliberately separate from the state machine: the attempt counter and the
 * lease live beside the state (5b), and a cancellation is a recorded request ({@code
 * cancelRequestedAt}) that never occupies the state machine — the executor lands {@code CANCELLED}
 * at a safe point. Only {@code SUCCEEDED} may stand next to a saved AIInsight conclusion; a partial
 * result never pretends to be a complete diagnosis.
 */
public final class AiRun {
    private AiRun() {}

    /** Run state machine. Terminal states never move again. */
    public enum Status {
        PENDING, RUNNING, WAITING_TOOL, SUCCEEDED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }

        public boolean canTransitionTo(Status next) {
            Objects.requireNonNull(next, "next");
            return switch (this) {
                case PENDING -> next == RUNNING || next == FAILED || next == CANCELLED;
                case RUNNING -> next == WAITING_TOOL || next == SUCCEEDED || next == FAILED || next == CANCELLED;
                case WAITING_TOOL -> next == RUNNING || next == FAILED || next == CANCELLED;
                case SUCCEEDED, FAILED, CANCELLED -> false;
            };
        }
    }

    /**
     * The closed set of landing reasons. {@code SUCCEEDED} never carries one; {@code FAILED} and
     * {@code CANCELLED} always do, so the registry keeps the failure or cancellation cause.
     */
    public enum Reason {
        RUNTIME_UNAVAILABLE, DEADLINE, BUDGET_EXHAUSTED, AUTHORIZATION_REVOKED,
        RESULT_LOST, EXECUTION_FAILED, CANCELLED_BY_REQUEST
    }

    /**
     * The closed submission. Tenant, subject, permissions and approval state are deliberately
     * absent: they come from the trusted authentication boundary only.
     */
    public record Command(UUID requestId, UUID incidentId, String question, ToolWindow window,
                          String knowledgeMode, InsightSubmission.SkillRef skill) {
        public Command {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(incidentId, "incidentId");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(skill, "skill");
            if (!"current".equals(knowledgeMode)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /** Content only: the request id is correlation metadata, never part of the digest. */
        public String commandDigest() {
            return hash(List.of("ai-run-command-v1", incidentId.toString(), question,
                window.from().toString(), window.to().toString(), knowledgeMode,
                skill.id(), skill.version(), skill.digest()));
        }

        private static String hash(List<String> parts) {
            try {
                var md = java.security.MessageDigest.getInstance("SHA-256");
                for (String part : parts) {
                    byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    md.update((bytes.length + ":").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    md.update(bytes);
                }
                return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
            } catch (java.security.NoSuchAlgorithmException missing) {
                throw new IllegalStateException();
            }
        }
    }

    /**
     * The durable run record. Only {@code status}, {@code statusVersion}, {@code updatedAt} and the
     * execution metadata ({@code attempt}, {@code cancelRequestedAt}, {@code reason}, {@code
     * disclosure}) ever change; the submission parts stay fixed so a replayed request keeps
     * returning the same run.
     *
     * <p>The attempt counter starts at 0 and grows with every executor claim (one run, many
     * attempts — recovery is never a second run). {@code cancelRequestedAt} is set at most once and
     * does not move the state machine. {@code reason} exists only on terminal runs and never on
     * {@code SUCCEEDED}. {@code disclosure} is the explicit duplicate-fee / adopted-result record of
     * the attempt that landed the run.
     */
    public record Run(UUID runId, TenantId tenantId, SubjectId subjectId, UUID requestId, String commandDigest,
                      UUID incidentId, String question, ToolWindow window, String knowledgeMode,
                      InsightSubmission.SkillRef skill, Status status, long statusVersion,
                      int attempt, Instant cancelRequestedAt, Reason reason, AiRunControl.Reconciliation disclosure,
                      Instant createdAt, Instant updatedAt) {
        public Run {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(incidentId, "incidentId");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (commandDigest == null || !commandDigest.matches("sha256:[0-9a-f]{64}")) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (!"current".equals(knowledgeMode)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (statusVersion < 0 || createdAt.isAfter(updatedAt)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (attempt < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (cancelRequestedAt != null && cancelRequestedAt.isBefore(createdAt)) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (reason != null && !status.terminal()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (status == Status.SUCCEEDED && reason != null) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if ((status == Status.FAILED || status == Status.CANCELLED) && reason == null) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (disclosure != null && attempt < 1) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** A fresh submission: attempt 0, no cancellation, no landing reason or disclosure. */
        public Run(UUID runId, TenantId tenantId, SubjectId subjectId, UUID requestId, String commandDigest,
                   UUID incidentId, String question, ToolWindow window, String knowledgeMode,
                   InsightSubmission.SkillRef skill, Status status, long statusVersion,
                   Instant createdAt, Instant updatedAt) {
            this(runId, tenantId, subjectId, requestId, commandDigest, incidentId, question, window, knowledgeMode,
                skill, status, statusVersion, 0, null, null, null, createdAt, updatedAt);
        }

        /** A replay must name this run with this content; anything else is an explicit conflict. */
        public void require(Command command) {
            if (!requestId.equals(command.requestId()) || !commandDigest.equals(command.commandDigest())
                || !incidentId.equals(command.incidentId())) {
                throw new Conflict("AI_RUN_REQUEST_REUSED");
            }
        }

        /** Pure transition. The store applies it with a compare-and-set on {@code statusVersion}. */
        public Run transitionTo(Status next, Instant at) {
            return transitionTo(new Transition(next, null, null, at));
        }

        /**
         * Pure transition with its landing metadata. A terminal landing keeps the reason and the
         * reconciliation disclosure beside the state; a non-terminal move must carry neither.
         */
        public Run transitionTo(Transition transition) {
            Objects.requireNonNull(transition, "transition");
            var next = transition.next();
            var at = transition.at();
            Objects.requireNonNull(at, "at");
            if (!status.canTransitionTo(next)) throw new Conflict("AI_RUN_STATE_CONFLICT");
            if (at.isBefore(updatedAt)) throw new Conflict("AI_RUN_STATE_CONFLICT");
            if (next == Status.SUCCEEDED || next == Status.FAILED || next == Status.CANCELLED) {
                return new Run(runId, tenantId, subjectId, requestId, commandDigest, incidentId, question, window,
                    knowledgeMode, skill, next, statusVersion + 1, attempt, cancelRequestedAt,
                    transition.reason(), transition.disclosure() == null ? disclosure : transition.disclosure(),
                    createdAt, at);
            }
            return new Run(runId, tenantId, subjectId, requestId, commandDigest, incidentId, question, window,
                knowledgeMode, skill, next, statusVersion + 1, attempt, cancelRequestedAt, reason, disclosure,
                createdAt, at);
        }

        /**
         * The executor claim: one new attempt on the same run. From {@code PENDING} and
         * {@code WAITING_TOOL} this is the legal move into {@code RUNNING}; from {@code RUNNING} it
         * is lease churn on the same state (a crashed worker's run reclaimed after its lease
         * expired), which is not a state-machine transition but still advances the CAS version and
         * the fencing token. Terminal runs can never be claimed.
         */
        public Run claimed(Instant at) {
            Objects.requireNonNull(at, "at");
            if (status.terminal()) throw new Conflict("AI_RUN_STATE_CONFLICT");
            if (at.isBefore(updatedAt)) throw new Conflict("AI_RUN_STATE_CONFLICT");
            if (status != Status.RUNNING && !status.canTransitionTo(Status.RUNNING)) {
                throw new Conflict("AI_RUN_STATE_CONFLICT");
            }
            return new Run(runId, tenantId, subjectId, requestId, commandDigest, incidentId, question, window,
                knowledgeMode, skill, Status.RUNNING, statusVersion + 1, attempt + 1, cancelRequestedAt,
                reason, disclosure, createdAt, at);
        }

        /**
         * Record the cancellation request. It never occupies the state machine: the status and the
         * CAS version do not move, and the request is recorded at most once (the first one wins).
         * The executor lands {@code CANCELLED} at its next safe point; a run that completes first
         * still ends {@code SUCCEEDED}.
         */
        public Run cancelRequested(Instant at) {
            Objects.requireNonNull(at, "at");
            if (status.terminal()) throw new Conflict("AI_RUN_STATE_CONFLICT");
            if (at.isBefore(createdAt)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (cancelRequestedAt != null) return this;
            return new Run(runId, tenantId, subjectId, requestId, commandDigest, incidentId, question, window,
                knowledgeMode, skill, status, statusVersion, attempt, at, reason, disclosure, createdAt, updatedAt);
        }
    }

    /** One state move with its landing metadata; the store applies it under CAS and fencing. */
    public record Transition(Status next, Reason reason, AiRunControl.Reconciliation disclosure, Instant at) {
        public Transition {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(at, "at");
            boolean terminal = next.terminal();
            if (reason != null && next != Status.FAILED && next != Status.CANCELLED) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (disclosure != null && !terminal) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if ((next == Status.FAILED || next == Status.CANCELLED) && reason == null) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        public static Transition to(Status next, Instant at) {
            return new Transition(next, null, null, at);
        }
    }

    /**
     * One bounded page over the retained ordering {@code createdAt DESC, runId DESC}. The cursor
     * names the last returned run; continuation is strictly after it, so later inserts never leak
     * into following pages and pages neither overlap nor reorder.
     */
    public record Page(List<Run> items, String nextCursor, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
            if (items.size() > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if ((nextCursor == null) == hasMore) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (hasMore && items.isEmpty()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** Assemble a page from at most {@code limit + 1} ordered rows. */
        public static Page of(List<Run> rows, int limit) {
            if (limit < 1 || limit > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            boolean hasMore = rows.size() > limit;
            return new Page(items, hasMore ? cursor(items.get(items.size() - 1).runId()) : null, hasMore);
        }
    }

    private static final Comparator<Run> ORDER = Comparator.comparing(Run::createdAt)
        .thenComparing(run -> run.runId().toString()).reversed();

    /** The one retained ordering of a run list; every implementation must return it identically. */
    public static List<Run> order(List<Run> rows) {
        var ordered = new ArrayList<>(rows);
        ordered.sort(ORDER);
        return List.copyOf(ordered);
    }

    /** Rows strictly after the cursor in the retained ordering. Unknown cursors fail closed. */
    public static List<Run> strictlyAfter(List<Run> ordered, String after) {
        if (after == null) return ordered;
        var cursor = cursor(after);
        for (int i = 0; i < ordered.size(); i++) {
            if (cursor.equals(ordered.get(i).runId().toString())) return ordered.subList(i + 1, ordered.size());
        }
        throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
    }

    /** The immutable cursor of one run: its run id. */
    public static String cursor(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return runId.toString();
    }

    /** Parse and shape-check a client cursor. Illegal shape is an invalid request, never a guess. */
    public static String cursor(String after) {
        if (after == null || !after.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        return after;
    }

    /** Explicit stable failures; the wire never echoes operator text. */
    public static final class Conflict extends RuntimeException {
        private final String code;

        public Conflict(String code) { super(code); this.code = code; }

        public String code() { return code; }
    }
}
