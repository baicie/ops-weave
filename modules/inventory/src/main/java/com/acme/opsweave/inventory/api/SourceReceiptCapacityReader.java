package com.acme.opsweave.inventory.api;

import com.acme.opsweave.sharedkernel.TenantId;

/**
 * Counts the receipts a source already stores, so the fixed caps can be seen before a write is
 * refused. Read-only: counting never removes a receipt and never authorizes a write.
 */
public interface SourceReceiptCapacityReader {
    int snapshotReceipts(TenantId tenantId, String sourceInstanceId);

    int correctionReceipts(TenantId tenantId, String sourceInstanceId);
}
