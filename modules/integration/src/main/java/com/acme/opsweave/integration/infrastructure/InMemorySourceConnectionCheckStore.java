package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.SourceConnectionCheckStore;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Development store; receipts disappear on restart. */
public final class InMemorySourceConnectionCheckStore implements SourceConnectionCheckStore {
    private final Map<String, List<SourceConnectionCheck>> byScope = new ConcurrentHashMap<>();

    @Override
    public synchronized void record(SourceConnectionCheck check) {
        List<SourceConnectionCheck> kept = new ArrayList<>(byScope.getOrDefault(scope(check), List.of()));
        kept.add(check);
        kept.sort(Comparator.comparing(SourceConnectionCheck::checkedAt).reversed()
            .thenComparing(SourceConnectionCheck::id, Comparator.reverseOrder()));
        byScope.put(scope(check), List.copyOf(kept.subList(0, Math.min(kept.size(), SourceConnectionCheck.MAX_KEPT))));
    }

    @Override
    public synchronized List<SourceConnectionCheck> recent(TenantId tenantId, String sourceInstanceId, int limit) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("Invalid check limit");
        }
        List<SourceConnectionCheck> kept = byScope.getOrDefault(tenantId.value() + "\u0000" + sourceInstanceId, List.of());
        return List.copyOf(kept.subList(0, Math.min(kept.size(), limit)));
    }

    private static String scope(SourceConnectionCheck check) {
        return check.tenantId().value() + "\u0000" + check.sourceInstanceId();
    }

    @Override
    public synchronized int kept(TenantId tenantId, String sourceInstanceId) {
        return byScope.getOrDefault(tenantId.value() + "\u0000" + sourceInstanceId, List.of()).size();
    }
}
