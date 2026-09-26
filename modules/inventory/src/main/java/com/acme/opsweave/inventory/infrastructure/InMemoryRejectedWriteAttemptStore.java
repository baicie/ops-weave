package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.RejectedWriteAttemptStore;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory refusal log. Not a production audit store. */
public final class InMemoryRejectedWriteAttemptStore implements RejectedWriteAttemptStore {
    private final Map<String, List<RejectedWriteAttempt>> attempts = new ConcurrentHashMap<>();
    private final RejectedWriteAttempt.Policy policy;

    public InMemoryRejectedWriteAttemptStore() {
        this(RejectedWriteAttempt.Policy.defaults());
    }

    public InMemoryRejectedWriteAttemptStore(RejectedWriteAttempt.Policy policy) {
        this.policy = policy;
    }

    @Override
    public synchronized void record(RejectedWriteAttempt attempt) {
        String key = key(attempt.tenantId(), attempt.sourceInstanceId());
        List<RejectedWriteAttempt> kept = new ArrayList<>(attempts.getOrDefault(key, List.of()));
        kept.add(attempt);
        kept.sort(Comparator.comparing(RejectedWriteAttempt::attemptedAt).reversed()
            .thenComparing(RejectedWriteAttempt::id, Comparator.reverseOrder()));
        attempts.put(key, List.copyOf(kept.subList(0, Math.min(kept.size(), policy.maxPerSource()))));
    }

    @Override
    public List<RejectedWriteAttempt> recent(TenantId tenantId, String sourceInstanceId, int limit) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("Invalid audit limit");
        }
        List<RejectedWriteAttempt> kept = attempts.getOrDefault(key(tenantId, sourceInstanceId), List.of());
        return List.copyOf(kept.subList(0, Math.min(kept.size(), limit)));
    }

    @Override
    public int kept(TenantId tenantId, String sourceInstanceId) {
        return attempts.getOrDefault(key(tenantId, sourceInstanceId), List.of()).size();
    }

    private static String key(TenantId tenantId, String sourceInstanceId) {
        return tenantId.value() + "\u0000" + sourceInstanceId;
    }
}
