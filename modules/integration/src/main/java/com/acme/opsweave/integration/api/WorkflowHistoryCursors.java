package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.WorkflowHistory.Cursor;
import com.acme.opsweave.integration.domain.WorkflowQuality.Reference;
import com.acme.opsweave.sharedkernel.TenantId;

/** Deployment adapter binds opaque pagination to trusted tenant, owner and fixed version. */
public interface WorkflowHistoryCursors {
    void requireAvailable();
    String seal(TenantId tenant,String owner,Reference reference,Cursor cursor);
    Cursor open(TenantId tenant,String owner,Reference reference,String token);
}
