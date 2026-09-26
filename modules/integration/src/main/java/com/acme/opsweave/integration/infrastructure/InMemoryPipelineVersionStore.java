package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.PipelineVersionStore;
import com.acme.opsweave.integration.domain.PipelineException;
import com.acme.opsweave.integration.domain.PipelineException.Code;
import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Development store; publications and pins disappear on restart. */
public final class InMemoryPipelineVersionStore implements PipelineVersionStore {
    private record Key(TenantId tenant, String source, String id, int revision) {}
    private record RunKey(TenantId tenant, String source, UUID run) {}
    private final Map<Key, PipelineVersion> versions = new HashMap<>();
    private final Map<RunKey, PipelineVersion.Ref> pins = new HashMap<>();

    public synchronized PipelineVersion publish(TenantId tenant, String source, PipelineVersion version) {
        var key = new Key(tenant, source, version.definition().id(), version.definition().revision());
        var existing = versions.putIfAbsent(key, version);
        if (existing != null && !existing.digest().equals(version.digest())) throw new PipelineException(Code.VERSION_CONFLICT);
        return existing == null ? version : existing;
    }
    public synchronized Optional<PipelineVersion> find(TenantId tenant, String source, String id, int revision) {
        return Optional.ofNullable(versions.get(new Key(tenant, source, id, revision)));
    }
    public synchronized void pin(TenantId tenant, String source, UUID run, PipelineVersion.Ref ref) {
        var version = find(tenant, source, ref.id(), ref.revision()).orElseThrow(() -> new PipelineException(Code.NOT_FOUND));
        if (!version.ref().equals(ref)) throw new PipelineException(Code.DIGEST_MISMATCH);
        var existing = pins.putIfAbsent(new RunKey(tenant, source, run), ref);
        if (existing != null && !existing.equals(ref)) throw new PipelineException(Code.VERSION_CONFLICT);
    }
    public synchronized Optional<PipelineVersion.Ref> pinned(TenantId tenant, String source, UUID run) {
        return Optional.ofNullable(pins.get(new RunKey(tenant, source, run)));
    }
}
