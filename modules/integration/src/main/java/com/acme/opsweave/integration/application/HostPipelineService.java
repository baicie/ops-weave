package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.PipelineVersionStore;
import com.acme.opsweave.integration.api.RawRecordReader;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.PipelineEvaluation.Host;
import com.acme.opsweave.integration.domain.PipelineEvaluation.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** No connector, inventory writer, notification or action port is reachable from this service. */
public final class HostPipelineService {
    private final AuthorizationService authorization;
    private final PipelineVersionStore versions;
    private final RawRecordReader raw;
    private final SyncRunStore runs;
    private final String source;
    private final Semaphore budget = new Semaphore(2);
    private final ZabbixHostMapper mapper = new ZabbixHostMapper();

    public HostPipelineService(AuthorizationService authorization, PipelineVersionStore versions,
            RawRecordReader raw, SyncRunStore runs, String source) {
        this.authorization = authorization;
        this.versions = versions;
        this.raw = raw;
        this.runs = runs;
        this.source = source;
    }

    public PipelineVersion publish(Principal principal, PipelineDefinition definition) {
        authorize(principal);
        var version = PipelineVersion.of(definition);
        var builtIn = PipelineVersion.of(PipelineDefinition.zabbixHostV1());
        if (definition.id().equals(builtIn.definition().id()) && definition.revision() == 1
            && !version.digest().equals(builtIn.digest())) throw error(PipelineException.Code.VERSION_CONFLICT);
        return versions.publish(principal.tenantId(), source, version);
    }

    public PipelineVersion get(Principal principal, String id, int revision) {
        authorize(principal);
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || revision < 1) {
            throw error(PipelineException.Code.INVALID_REQUEST);
        }
        return versions.find(principal.tenantId(), source, id, revision)
            .orElseThrow(() -> error(PipelineException.Code.NOT_FOUND));
    }

    public PipelineEvaluation preview(Principal principal, UUID runId, PipelineDefinition draft, int limit) {
        authorize(principal);
        return evaluate(principal, runId, PipelineVersion.of(draft), limit, "PREVIEW", "VALIDATE_MAPPING");
    }

    public PipelineEvaluation replay(Principal principal, UUID runId, PipelineVersion.Ref target, int limit,
            boolean dryRun, String purpose) {
        authorize(principal);
        if (!dryRun || !List.of("VALIDATE_MAPPING", "COMPARE_VERSION").contains(purpose)) {
            throw error(PipelineException.Code.INVALID_REQUEST);
        }
        return evaluate(principal, runId, resolve(principal, target), limit, "REPLAY", purpose);
    }

    private PipelineVersion resolve(Principal principal, PipelineVersion.Ref ref) {
        var version = get(principal, ref.id(), ref.revision());
        if (!version.digest().equals(ref.digest())) throw error(PipelineException.Code.DIGEST_MISMATCH);
        return version;
    }

    private PipelineEvaluation evaluate(Principal principal, UUID runId, PipelineVersion target, int limit, String mode, String purpose) {
        if (limit < 1 || limit > RawRecordReader.MAX_RECORDS) throw error(PipelineException.Code.INVALID_REQUEST);
        if (!budget.tryAcquire()) throw error(PipelineException.Code.REPLAY_BUSY);
        try {
            var run = runs.find(principal.tenantId(), runId)
                .filter(item -> item.sourceInstanceId().equals(source) && item.objectType().equals("host"))
                .orElseThrow(() -> error(PipelineException.Code.NOT_FOUND));
            if (run.status() == SyncStatus.RUNNING) throw error(PipelineException.Code.RUN_NOT_READY);
            var originalRef = versions.pinned(principal.tenantId(), source, runId)
                .orElseThrow(() -> error(PipelineException.Code.LINEAGE_UNAVAILABLE));
            var original = resolve(principal, originalRef);
            var batch = raw.read(principal.tenantId(), source, runId, limit);
            if (batch.records().size() > limit || batch.retainedCount() < batch.records().size()) {
                throw new IllegalStateException("Raw reader exceeded its contract");
            }
            List<Row> rows = new ArrayList<>();
            int accepted = 0, rejected = 0, changed = 0, oversized = 0;
            for (var retained : batch.records()) {
                if (retained.record() == null) {
                    oversized++;
                    rows.add(new Row(retained.ref(), "RAW_TOO_LARGE", null, null, false));
                    continue;
                }
                var record = retained.record();
                var reason = mapper.rejectReason(record.payload());
                if (reason.isPresent()) {
                    rejected++;
                    rows.add(new Row(retained.ref(), "MISSING_HOST_ID", null, null, false));
                    continue;
                }
                var before = mapOrReject(original, principal, record, run, retained.ref());
                var after = mapOrReject(target, principal, record, run, retained.ref());
                var readable = after != null ? after : before;
                if (readable != null && authorization.authorize(principal, ResourceRef.entity(principal.tenantId(), readable.entity().id()),
                    Permission.ENTITY_READ).denied()) throw error(PipelineException.Code.FORBIDDEN);
                var previous = before == null ? null : summary(before);
                var candidate = after == null ? null : summary(after);
                boolean differs = !Objects.equals(previous, candidate);
                if (differs) changed++;
                if (after == null) rejected++; else accepted++;
                rows.add(new Row(retained.ref(), after == null ? "INVALID_HOST" : "ACCEPTED", previous, candidate, differs));
            }
            long missing = Math.max(0, (long) run.fetched() - batch.retainedCount());
            boolean truncated = batch.retainedCount() > rows.size();
            return new PipelineEvaluation(mode, purpose, true, false, source, runId.toString(), run.status().name(),
                run.dataMode(), originalRef, target.ref(), run.fetched(), batch.retainedCount(), missing,
                truncated, oversized, accepted, rejected, changed,
                rejected > 0 && target.definition().errorPolicy() == ErrorPolicy.FAIL_FAST,
                List.copyOf(rows));
        } finally { budget.release(); }
    }

    private static Host summary(ZabbixHostMapper.MappedHost mapped) {
        return new Host(mapped.entity().id().value().toString(), mapped.entity().name(),
            Objects.toString(mapped.entity().attributes().get("ip"), ""), mapped.entity().lifecycle().name());
    }
    private ZabbixHostMapper.MappedHost mapOrReject(PipelineVersion version, Principal principal,
            com.acme.opsweave.integration.api.Connector.RawRecord record, SyncRun run, String ref) {
        try {
            return mapper.map(version.definition(), principal.tenantId(), source, record.payload(),
                record.observedAt(), run.startedAt(), ref);
        } catch (IllegalArgumentException rejected) { return null; }
    }
    private void authorize(Principal principal) {
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()) {
            throw error(PipelineException.Code.FORBIDDEN);
        }
    }
    private static PipelineException error(PipelineException.Code code) { return new PipelineException(code); }
}
