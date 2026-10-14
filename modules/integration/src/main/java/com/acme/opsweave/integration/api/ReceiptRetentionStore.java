package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the receipt retention view and its explicit cleanup. The store reports and removes only
 * what the domain marks as cleanable; it never sweeps on its own and never touches protected rows.
 */
public interface ReceiptRetentionStore {
    /** Oldest cleanable candidate first, bounded; the flag is resolved by the store's protection rule. */
    List<ReceiptRetention.Row> rows(TenantId tenant, ReceiptRetention.Kind kind, String scopeKey, int limit);

    /** Every scope of one kind the tenant holds, so the budget is visible without guessing keys. */
    List<String> scopes(TenantId tenant, ReceiptRetention.Kind kind);

    /** Remove exactly these rows; any protected id is ignored and counted as retained. */
    int remove(TenantId tenant, ReceiptRetention.Kind kind, String scopeKey, List<String> ids);

    Optional<ReceiptRetention.Receipt> command(TenantId tenant, UUID requestId);

    void addCommand(TenantId tenant, ReceiptRetention.Receipt receipt);
}
