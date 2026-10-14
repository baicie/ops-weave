package com.acme.opsweave.aicontrol.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The ordered run event log (M5 5c, ADR-104 §2.5).
 *
 * <p><b>Events are records, not commands.</b> One {@link Event} is one durable fact of a run's
 * lifecycle: {@code runId}, a per-run monotonic {@code seq}, a closed {@code type}, {@code
 * occurredAt}, a {@code payloadVersion} and a closed {@code payload}. Nothing in this class, its
 * payload or its wire form can instruct an executor: replaying a sequence, reconnecting a reader or
 * re-reading a page only re-delivers records — it never executes a tool, calls a model or sends a
 * notification.
 *
 * <p>The payload is deliberately small and closed: a status, an attempt counter, a landing reason
 * and the explicit reconciliation disclosure. There is no hidden reasoning, no free-form model text,
 * no credential and no raw log or evidence body — those stay in the resources that own them
 * (AIInsight, tool read sessions, model spend receipts).
 *
 * <p>Ordering and gaps: {@code seq} starts at 1 and grows by one per recorded fact of one run. The
 * server defines the retained window ({@link #RETAINED_EVENTS} per run); a cursor that points before
 * that window has expired, and the readback answers with the retained window plus {@code gap=true}
 * and the current run snapshot instead of pretending the missing events never existed.
 */
public final class AiRunEvent {
    private AiRunEvent() {}

    /** The payload schema version of every event written by this platform build. */
    public static final int PAYLOAD_VERSION = 1;

    /**
     * The server-defined retained window of one run's event log (ADR-104 §2.5: "保留窗口由服务端定义").
     * Older events are dropped on append; a reader whose cursor points below the retained window gets
     * an explicit gap. Capacity budgets and trimming observability are 5d work, not this window.
     */
    public static final int RETAINED_EVENTS = 200;

    /** The closed event type set. Anything else is not a run event. */
    public enum Type { SUBMITTED, CLAIMED, STATUS_CHANGED, TOOL_WAIT, CANCEL_REQUESTED, RECONCILED, FINISHED }

    /**
     * The closed payload of one event. Only the fields of the event's type may exist; the type and
     * the payload are checked against each other in {@link Event}. {@code disclosure} is the explicit
     * unknown-completion record of 5b (prior model calls, uncertain fees, duplicate-fee risk) — it is
     * a disclosure, never an instruction.
     */
    public record Payload(String status, Integer attempt, String reason, AiRunControl.Reconciliation disclosure) {
        public Payload {
            if (status != null && !statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (attempt != null && (attempt < 0 || attempt > 1_000_000)) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (reason != null && !reasonName(reason)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        public static Payload empty() {
            return new Payload(null, null, null, null);
        }

        /** A state record: just where the run stands. */
        public static Payload state(AiRun.Status status) {
            return new Payload(status.name(), null, null, null);
        }

        /** A claim record: the state plus the attempt the executor took. */
        public static Payload claim(AiRun.Status status, int attempt) {
            return new Payload(status.name(), attempt, null, null);
        }

        /** A terminal landing: the final state, its reason and the reconciliation disclosure. */
        public static Payload landing(AiRun.Status status, AiRun.Reason reason, AiRunControl.Reconciliation disclosure) {
            return new Payload(status.name(), null, reason == null ? null : reason.name(), disclosure);
        }

        /** A reconciliation record: the disclosure only. */
        public static Payload reconciliation(AiRunControl.Reconciliation disclosure) {
            return new Payload(null, null, null, Objects.requireNonNull(disclosure, "disclosure"));
        }
    }

    /**
     * One durable event record. The constructor closes type and payload against each other: a claim
     * carries an attempt, a cancellation request carries nothing, a reconciliation carries only its
     * disclosure and a terminal landing never carries an attempt.
     */
    public record Event(UUID runId, long seq, Type type, Instant occurredAt, int payloadVersion, Payload payload) {
        public Event {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(payload, "payload");
            if (seq < 1) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (payloadVersion != PAYLOAD_VERSION) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (!closed(type, payload)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        private static boolean closed(Type type, Payload p) {
            return switch (type) {
                case SUBMITTED -> "PENDING".equals(p.status()) && Integer.valueOf(0).equals(p.attempt())
                    && p.reason() == null && p.disclosure() == null;
                case CLAIMED -> "RUNNING".equals(p.status()) && p.attempt() != null && p.attempt() >= 1
                    && p.reason() == null && p.disclosure() == null;
                case TOOL_WAIT -> "WAITING_TOOL".equals(p.status()) && p.attempt() == null
                    && p.reason() == null && p.disclosure() == null;
                case STATUS_CHANGED -> ("RUNNING".equals(p.status()) || "WAITING_TOOL".equals(p.status()))
                    && p.attempt() == null && p.reason() == null && p.disclosure() == null;
                case CANCEL_REQUESTED -> p.status() == null && p.attempt() == null
                    && p.reason() == null && p.disclosure() == null;
                case RECONCILED -> p.status() == null && p.attempt() == null
                    && p.reason() == null && p.disclosure() != null;
                case FINISHED -> {
                    if (p.attempt() != null || p.status() == null) yield false;
                    var status = AiRun.Status.valueOf(p.status());
                    if (!status.terminal()) yield false;
                    if (p.reason() == null != (status == AiRun.Status.SUCCEEDED)) yield false;
                    yield true;
                }
            };
        }
    }

    /** A closed status name; anything outside the run state machine is rejected. */
    private static boolean statusName(String value) {
        for (var status : AiRun.Status.values()) if (status.name().equals(value)) return true;
        return false;
    }

    /** A closed landing reason name. */
    private static boolean reasonName(String value) {
        for (var reason : AiRun.Reason.values()) if (reason.name().equals(value)) return true;
        return false;
    }

    /** The retained sequence bounds of one run's event log; both are 0 when nothing is retained. */
    public record Bounds(long lowestSeq, long highestSeq) {
        public Bounds {
            if (lowestSeq < 0 || highestSeq < 0 || lowestSeq > highestSeq) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        public static Bounds none() {
            return new Bounds(0, 0);
        }
    }

    /** The current run state a reader falls back to; the snapshot never replaces the event log. */
    public record Snapshot(String status, long statusVersion, int attempt, Instant updatedAt) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (!statusName(status)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (statusVersion < 0 || attempt < 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        public static Snapshot of(AiRun.Run run) {
            return new Snapshot(run.status().name(), run.statusVersion(), run.attempt(), run.updatedAt());
        }

        public boolean terminal() {
            return AiRun.Status.valueOf(status).terminal();
        }
    }

    /**
     * One bounded readback page over the retained ordering {@code seq ASC}. The cursor is the {@code
     * seq} of the last item as decimal text; continuation is strictly after it, so pages neither
     * overlap nor reorder and a re-read of the same cursor returns the same records. {@code gap} is
     * explicit: the cursor expired and earlier events are no longer retained, so this page is the
     * retained window plus the current snapshot — never a fake continuous history.
     */
    public record Page(UUID runId, List<Event> items, String nextCursor, boolean hasMore, boolean gap, Snapshot snapshot) {
        public Page {
            Objects.requireNonNull(runId, "runId");
            items = List.copyOf(items);
            Objects.requireNonNull(snapshot, "snapshot");
            if (items.size() > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if ((nextCursor == null) == hasMore) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (hasMore && items.isEmpty()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (nextCursor != null && !nextCursor.equals(String.valueOf(items.get(items.size() - 1).seq()))) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            for (int i = 0; i < items.size(); i++) {
                var event = items.get(i);
                if (!runId.equals(event.runId())) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
                if (i > 0 && items.get(i - 1).seq() >= event.seq()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /**
         * Assemble a page from at most {@code limit + 1} ordered rows. The gap rule is exact: every
         * event after the cursor is retained (no gap) as soon as {@code afterSeq + 1 >= lowestSeq}.
         */
        public static Page of(UUID runId, List<Event> rows, long afterSeq, int limit, Bounds bounds, Snapshot snapshot) {
            if (afterSeq < 0 || afterSeq > 1_000_000_000_000L) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (limit < 1 || limit > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            boolean hasMore = rows.size() > limit;
            boolean gap = bounds.lowestSeq() > 0 && afterSeq + 1 < bounds.lowestSeq();
            return new Page(runId, items, hasMore ? String.valueOf(items.get(items.size() - 1).seq()) : null,
                hasMore, gap, snapshot);
        }
    }

    /** The submission record: the run entered the registry. */
    public static Event submitted(AiRun.Run run, long seq) {
        Objects.requireNonNull(run, "run");
        return new Event(run.runId(), seq, Type.SUBMITTED, run.createdAt(), PAYLOAD_VERSION,
            Payload.claim(AiRun.Status.PENDING, 0));
    }

    /** The claim record: one executor took one attempt on this run. */
    public static Event claimed(AiRun.Run run, long seq) {
        Objects.requireNonNull(run, "run");
        return new Event(run.runId(), seq, Type.CLAIMED, run.updatedAt(), PAYLOAD_VERSION,
            Payload.claim(AiRun.Status.RUNNING, run.attempt()));
    }

    /**
     * The record of one applied state move: {@code WAITING_TOOL} is the tool wait, a terminal landing
     * is the finish record (with its reason and disclosure), anything else is a status change.
     */
    public static Event transitioned(AiRun.Run run, long seq) {
        Objects.requireNonNull(run, "run");
        return switch (run.status()) {
            case WAITING_TOOL -> new Event(run.runId(), seq, Type.TOOL_WAIT, run.updatedAt(), PAYLOAD_VERSION,
                Payload.state(AiRun.Status.WAITING_TOOL));
            case SUCCEEDED, FAILED, CANCELLED -> new Event(run.runId(), seq, Type.FINISHED, run.updatedAt(),
                PAYLOAD_VERSION, Payload.landing(run.status(), run.reason(), run.disclosure()));
            case PENDING, RUNNING -> new Event(run.runId(), seq, Type.STATUS_CHANGED, run.updatedAt(),
                PAYLOAD_VERSION, Payload.state(run.status()));
        };
    }

    /** The recorded cancellation request. It is a fact about the run, never an instruction. */
    public static Event cancelRequested(UUID runId, long seq, Instant at) {
        return new Event(runId, seq, Type.CANCEL_REQUESTED, at, PAYLOAD_VERSION, Payload.empty());
    }

    /** The explicit unknown-completion disclosure of one reconciliation. */
    public static Event reconciled(UUID runId, long seq, AiRunControl.Reconciliation disclosure, Instant at) {
        return new Event(runId, seq, Type.RECONCILED, at, PAYLOAD_VERSION, Payload.reconciliation(disclosure));
    }

    /** The retained event ordering of one run: {@code seq ASC}. */
    public static List<Event> order(List<Event> rows) {
        var ordered = new ArrayList<>(rows);
        ordered.sort(java.util.Comparator.comparingLong(Event::seq));
        return List.copyOf(ordered);
    }

    /**
     * Parse the client cursor (the {@code afterSeq} query parameter): a decimal sequence number, 0
     * meaning "from the beginning of the retained window". An illegal shape is an invalid request,
     * never a guessed position.
     */
    public static long cursor(String afterSeq) {
        if (afterSeq == null) return 0;
        if (!afterSeq.matches("0|[1-9][0-9]{0,17}")) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        try {
            return Long.parseLong(afterSeq);
        } catch (NumberFormatException outOfBounds) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }
}
