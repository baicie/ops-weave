package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.AiRunRuntimeStateStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunCrossStore;
import com.acme.opsweave.aicontrol.domain.AiRunRuntimeState;
import com.acme.opsweave.sharedkernel.TenantId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory runtime state receipts with the same semantics as the PostgreSQL store: idempotent on
 * the request id, a bounded per-run window with counted drops and one current cross-store report
 * snapshot per run. Explicitly not durable — it never claims to survive a crash.
 */
public final class InMemoryAiRunRuntimeStateStore implements AiRunRuntimeStateStore {
    private record Stored(AiRunRuntimeState.Report report, long runSeq) {}

    private final Map<String, Stored> receipts = new LinkedHashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Long> dropped = new HashMap<>();
    private final Map<String, AiRunCrossStore.Report> reports = new HashMap<>();

    private static String key(TenantId tenant, UUID id) {
        return tenant.value() + "|" + id;
    }

    @Override
    public synchronized AiRunRuntimeState.Report save(TenantId tenant, AiRunRuntimeState.Report report) {
        var requestKey = key(tenant, report.requestId());
        var existing = receipts.get(requestKey);
        if (existing != null) {
            existing.report().require(report);
            return existing.report();
        }
        var runKey = key(tenant, report.runId());
        long seq = sequences.merge(runKey, 1L, Long::sum);
        receipts.put(requestKey, new Stored(report, seq));
        // The window is bounded: older receipts are dropped and the drop is counted, never hidden.
        for (var run : runsOf(tenant, report.runId())) {
            if (run.report().runId().equals(report.runId()) && run.runSeq() <= seq - AiRunRuntimeState.RETAINED_REPORTS) {
                receipts.remove(key(tenant, run.report().requestId()));
                dropped.merge(runKey, 1L, Long::sum);
            }
        }
        return report;
    }

    private List<Stored> runsOf(TenantId tenant, UUID runId) {
        var runs = new ArrayList<Stored>();
        for (var entry : receipts.values()) {
            if (entry.report().runId().equals(runId)) runs.add(entry);
        }
        return runs;
    }

    @Override
    public synchronized Optional<AiRunRuntimeState.Report> find(TenantId tenant, UUID requestId) {
        return Optional.ofNullable(receipts.get(key(tenant, requestId))).map(Stored::report);
    }

    @Override
    public synchronized Optional<AiRunRuntimeState.Report> latest(TenantId tenant, UUID runId) {
        return runsOf(tenant, runId).stream()
            .max(java.util.Comparator.comparingLong(Stored::runSeq))
            .map(Stored::report);
    }

    @Override
    public synchronized Counts counts(TenantId tenant, UUID runId) {
        var runKey = key(tenant, runId);
        return new Counts(runsOf(tenant, runId).size(), dropped.getOrDefault(runKey, 0L));
    }

    @Override
    public synchronized Optional<AiRunCrossStore.Report> findReport(TenantId tenant, UUID runId) {
        return Optional.ofNullable(reports.get(key(tenant, runId)));
    }

    @Override
    public synchronized void saveReport(TenantId tenant, AiRunCrossStore.Report report) {
        reports.put(key(tenant, report.runId()), report);
    }
}
