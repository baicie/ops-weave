package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Optional;
import java.util.UUID;

public interface SyncRunStore {
    SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode);

    void checkpoint(
        TenantId tenantId,
        UUID id,
        String cursor,
        int pages,
        int fetched,
        int accepted,
        int rejected
    );

    void succeed(TenantId tenantId, UUID id);

    void fail(TenantId tenantId, UUID id, String reason);

    Optional<SyncRun> find(TenantId tenantId, UUID id);
}
