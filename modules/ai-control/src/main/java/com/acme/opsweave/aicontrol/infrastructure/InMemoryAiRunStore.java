package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.AiRunEventStore;
import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory run registry with the same semantics as the PostgreSQL store: idempotent create on the
 * request id, one retained ordering, cursors that fail closed, compare-and-set state advance and the
 * 5b executor seam — lease claims with a fencing token, cooperative cancellation beside the
 * state machine and idempotent control receipts. The 5c event log is recorded at the same transition
 * points and keeps the same retained window and gap semantics.
 */
public final class InMemoryAiRunStore implements AiRunStore {
    private record Lease(long fence, String worker, Instant until, Instant deadline) {}

    private final Map<String, AiRun.Run> runs = new LinkedHashMap<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final Map<String, UUID> requests = new HashMap<>();
    private final Map<String, AiRunControl.CancelReceipt> cancelReceipts = new HashMap<>();
    private final Map<String, AiRunControl.RetryReceipt> retryReceipts = new HashMap<>();
    private final Map<String, List<AiRunEvent.Event>> events = new HashMap<>();
    private final int retained;

    public InMemoryAiRunStore() {
        this(AiRunEvent.RETAINED_EVENTS);
    }

    /** The retained window is a server constant; tests make it small to exercise the gap path. */
    public InMemoryAiRunStore(int retained) {
        if (retained < 1) throw new IllegalArgumentException("retained events out of bounds");
        this.retained = retained;
    }

    private static String key(TenantId tenant, UUID id) {
        return tenant.value() + "|" + id;
    }

    private AiRunEvent.Event appendLocked(TenantId tenant, UUID runId, java.util.function.LongFunction<AiRunEvent.Event> factory) {
        var log = events.computeIfAbsent(key(tenant, runId), ignored -> new ArrayList<>());
        long seq = log.isEmpty() ? 1 : log.get(log.size() - 1).seq() + 1;
        var event = factory.apply(seq);
        if (!event.runId().equals(runId) || event.seq() != seq) throw new IllegalStateException("Event sequence mismatch");
        log.add(event);
        while (log.size() > retained) log.remove(0);
        return event;
    }

    @Override
    public synchronized AiRunEvent.Event append(TenantId tenant, UUID runId, AiRunEvent.Type type, Instant occurredAt,
            AiRunEvent.Payload payload) {
        if (!runs.containsKey(key(tenant, runId))) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        return appendLocked(tenant, runId, seq -> new AiRunEvent.Event(runId, seq, type, occurredAt,
            AiRunEvent.PAYLOAD_VERSION, payload));
    }

    @Override
    public synchronized List<AiRunEvent.Event> read(TenantId tenant, UUID runId, long afterSeq, int limit) {
        if (afterSeq < 0 || limit < 1) throw new IllegalArgumentException("event cursor out of bounds");
        var log = events.get(key(tenant, runId));
        if (log == null) return List.of();
        var rows = new ArrayList<AiRunEvent.Event>();
        for (var event : log) {
            if (event.seq() > afterSeq) rows.add(event);
            if (rows.size() >= limit) break;
        }
        return List.copyOf(rows);
    }

    @Override
    public synchronized AiRunEvent.Bounds bounds(TenantId tenant, UUID runId) {
        var log = events.get(key(tenant, runId));
        if (log == null || log.isEmpty()) return AiRunEvent.Bounds.none();
        return new AiRunEvent.Bounds(log.get(0).seq(), log.get(log.size() - 1).seq());
    }

    @Override
    public synchronized AiRunEventStore.Retention retention(TenantId tenant, UUID runId) {
        var log = events.get(key(tenant, runId));
        if (log == null || log.isEmpty()) return new AiRunEventStore.Retention(retained, 0, 0, false);
        long dropped = log.get(0).seq() - 1;
        return new AiRunEventStore.Retention(retained, log.size(), dropped, dropped > 0);
    }

    @Override
    public synchronized AiRun.Run create(AiRun.Run run) {
        var requestKey = key(run.tenantId(), run.requestId());
        if (cancelReceipts.containsKey(requestKey) || retryReceipts.containsKey(requestKey)) {
            throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        }
        var existing = requests.get(requestKey);
        if (existing != null) return runs.get(key(run.tenantId(), existing));
        runs.put(key(run.tenantId(), run.runId()), run);
        requests.put(requestKey, run.runId());
        appendLocked(run.tenantId(), run.runId(), seq -> AiRunEvent.submitted(run, seq));
        return run;
    }

    @Override
    public synchronized Optional<AiRun.Run> find(TenantId tenant, UUID runId) {
        return Optional.ofNullable(runs.get(key(tenant, runId)));
    }

    @Override
    public synchronized Optional<AiRun.Run> byRequest(TenantId tenant, UUID requestId) {
        var runId = requests.get(key(tenant, requestId));
        return runId == null ? Optional.empty() : Optional.ofNullable(runs.get(key(tenant, runId)));
    }

    @Override
    public synchronized List<AiRun.Run> page(TenantId tenant, SubjectId owner, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        var visible = new ArrayList<AiRun.Run>();
        for (var run : runs.values()) {
            if (run.tenantId().equals(tenant) && run.subjectId().equals(owner)) visible.add(run);
        }
        var ordered = AiRun.order(visible);
        var afterCursor = AiRun.strictlyAfter(ordered, after);
        var page = new ArrayList<AiRun.Run>();
        for (int i = 0; i < afterCursor.size() && i < limit; i++) page.add(afterCursor.get(i));
        return List.copyOf(page);
    }

    @Override
    public synchronized Optional<Claim> claim(TenantId tenant, UUID runId, String worker, Instant now,
            Duration lease, Duration deadline) {
        var runKey = key(tenant, runId);
        var current = runs.get(runKey);
        if (current == null || current.status().terminal()) return Optional.empty();
        var held = leases.get(runKey);
        if (held != null && held.until().isAfter(now)) return Optional.empty();
        var claimed = current.claimed(now);
        var fence = held == null ? 1 : held.fence() + 1;
        leases.put(runKey, new Lease(fence, worker, now.plus(lease), now.plus(deadline)));
        runs.put(runKey, claimed);
        appendLocked(tenant, runId, seq -> AiRunEvent.claimed(claimed, seq));
        return Optional.of(new Claim(claimed, fence));
    }

    @Override
    public synchronized Optional<AiRun.Run> advance(TenantId tenant, UUID runId, long expectedVersion, long fence,
            AiRun.Transition next) {
        var runKey = key(tenant, runId);
        var current = runs.get(runKey);
        if (current == null || current.statusVersion() != expectedVersion) return Optional.empty();
        var held = leases.get(runKey);
        if (held == null ? fence != 0 : held.fence() != fence) return Optional.empty();
        var updated = current.transitionTo(next);
        runs.put(runKey, updated);
        if (updated.status().terminal()) leases.remove(runKey);
        appendLocked(tenant, runId, seq -> AiRunEvent.transitioned(updated, seq));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<AiRun.Run> requestCancel(TenantId tenant, UUID runId, Instant at) {
        var runKey = key(tenant, runId);
        var current = runs.get(runKey);
        if (current == null || current.status().terminal()) return Optional.empty();
        var updated = current.cancelRequested(at);
        boolean fresh = current.cancelRequestedAt() == null;
        runs.put(runKey, updated);
        // The request is recorded at most once: only the first one gets an event record.
        if (fresh) appendLocked(tenant, runId, seq -> AiRunEvent.cancelRequested(runId, seq, at));
        return Optional.of(updated);
    }

    @Override
    public synchronized List<AiRun.Run> claimable(TenantId tenant, SubjectId owner, Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("claimable limit out of bounds");
        var candidates = new ArrayList<AiRun.Run>();
        for (var run : runs.values()) {
            if (!run.tenantId().equals(tenant) || !run.subjectId().equals(owner)) continue;
            var held = leases.get(key(tenant, run.runId()));
            boolean free = held == null || !held.until().isAfter(now);
            if (!run.status().terminal() && free
                && (run.status() == AiRun.Status.PENDING || held != null)) candidates.add(run);
        }
        candidates.sort(java.util.Comparator.comparing(AiRun.Run::createdAt)
            .thenComparing(run -> run.runId().toString()));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    @Override
    public synchronized Optional<AiRunControl.CancelReceipt> findCancel(TenantId tenant, UUID requestId) {
        return Optional.ofNullable(cancelReceipts.get(key(tenant, requestId)));
    }

    @Override
    public synchronized Optional<AiRunControl.RetryReceipt> findRetry(TenantId tenant, UUID requestId) {
        return Optional.ofNullable(retryReceipts.get(key(tenant, requestId)));
    }

    @Override
    public synchronized AiRunControl.CancelReceipt saveCancel(TenantId tenant, AiRunControl.CancelReceipt receipt) {
        var requestKey = key(tenant, receipt.requestId());
        if (requests.containsKey(requestKey)) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        var existing = cancelReceipts.get(requestKey);
        if (existing != null) {
            existing.require(new AiRunControl.Command(AiRunControl.Kind.CANCEL, receipt.requestId(), receipt.runId()));
            return existing;
        }
        if (retryReceipts.containsKey(requestKey)) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        cancelReceipts.put(requestKey, receipt);
        return receipt;
    }

    @Override
    public synchronized AiRunControl.RetryReceipt saveRetry(TenantId tenant, AiRunControl.RetryReceipt receipt) {
        var requestKey = key(tenant, receipt.requestId());
        if (requests.containsKey(requestKey)) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        var existing = retryReceipts.get(requestKey);
        if (existing != null) {
            existing.require(new AiRunControl.Command(AiRunControl.Kind.RETRY, receipt.requestId(), receipt.runId()));
            return existing;
        }
        if (cancelReceipts.containsKey(requestKey)) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
        retryReceipts.put(requestKey, receipt);
        return receipt;
    }
}
