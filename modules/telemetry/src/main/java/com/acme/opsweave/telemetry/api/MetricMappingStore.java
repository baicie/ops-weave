package com.acme.opsweave.telemetry.api;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.*;
import java.util.*;
import java.util.function.Function;

/** Binding CAS and original receipts share a transaction; source refresh uses the same exclusion. */
public interface MetricMappingStore {
    <T> T mappingTransaction(TenantId tenant, Function<Session,T> operation);
    interface Session {
        Optional<MetricBinding> binding(String source, String item);
        Optional<MetricDefinition> definition(String key);
        List<MetricBinding> bindings(com.acme.opsweave.identity.domain.ResourceScope scope); // At most 201 tenant rows; public reads return at most 20 authorized rows.
        Optional<MetricMappingReceipt> receipt(String owner, UUID request);
        int receiptCount(String owner);
        void replace(MetricBinding next, long expectedVersion);
        void addReceipt(String owner, MetricMappingReceipt receipt);
    }
}
