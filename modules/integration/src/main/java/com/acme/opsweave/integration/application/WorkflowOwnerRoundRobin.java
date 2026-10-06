package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.domain.WorkflowFailure;
import java.util.ArrayDeque;
import java.util.Objects;

/** Bounded in-process owner rotation for background polling. */
public final class WorkflowOwnerRoundRobin<T> {
    private final int capacity;
    private final ArrayDeque<T> owners = new ArrayDeque<>();

    public WorkflowOwnerRoundRobin(int capacity) {
        if (capacity < 1 || capacity > 40) throw new IllegalArgumentException("Invalid owner capacity");
        this.capacity = capacity;
    }

    public synchronized void remember(T owner) {
        Objects.requireNonNull(owner, "owner");
        if (owners.contains(owner)) return;
        if (owners.size() >= capacity) throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
        owners.addLast(owner);
    }

    /** Returns the next owner and moves it to the back of the rotation. */
    public synchronized T next() {
        var owner = owners.pollFirst();
        if (owner == null) return null;
        owners.addLast(owner);
        return owner;
    }

    public synchronized boolean remove(T owner) { return owners.remove(owner); }
    public synchronized int size() { return owners.size(); }
}
