package com.acme.opsweave.integration.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;

/** Trusted operator configuration. Never constructed from an incoming model or HTTP request. */
public record HistoryStream(TenantId tenantId, String sourceInstanceId, String itemId, String streamName) {
    public HistoryStream {
        Objects.requireNonNull(tenantId, "tenantId");
        if (!sourceInstanceId.matches("[a-zA-Z0-9_.-]{1,64}") || !itemId.matches("[1-9][0-9]{0,19}")
            || !streamName.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid history stream");
    }
}
