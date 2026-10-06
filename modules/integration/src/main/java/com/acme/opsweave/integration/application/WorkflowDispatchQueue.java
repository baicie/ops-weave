package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.domain.WorkflowDiagnostics;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/** One bounded local owner dispatch; queued task snapshots never replace current authorization. */
public final class WorkflowDispatchQueue<T> {
    public static final int CAPACITY = 21;
    private record Pending<T>(T task, UUID id, Instant enqueuedAt) {}
    public record Delivery<T>(T task, WorkflowDiagnostics.Dispatch dispatch) {}
    private final Clock clock;
    private final ArrayDeque<Pending<T>> pending = new ArrayDeque<>();

    public WorkflowDispatchQueue(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    public void add(T task) {
        Objects.requireNonNull(task);
        if (pending.size() >= CAPACITY) throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
        pending.addLast(new Pending<>(task, UUID.randomUUID(), now()));
    }
    public Delivery<T> poll() {
        var next = pending.pollFirst();
        return next == null ? null : new Delivery<>(next.task(), new WorkflowDiagnostics.Dispatch(next.id(), next.enqueuedAt(), now()));
    }
    public int size() { return pending.size(); }
}
