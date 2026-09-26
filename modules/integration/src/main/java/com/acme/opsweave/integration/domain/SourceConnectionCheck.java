package com.acme.opsweave.integration.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One bounded, read-only source self-check. The receipt says what the source reported at one moment:
 * {@code reportedVersion} is the source's own claim to review, never a verified support statement, and
 * {@code statusCode} is a stable code rather than vendor text. A check never authorizes a scan.
 */
public record SourceConnectionCheck(
    UUID id,
    TenantId tenantId,
    String sourceInstanceId,
    String actor,
    Instant checkedAt,
    String dataMode,
    boolean reachable,
    String statusCode,
    String reportedVersion
) {
    /** Receipts kept per tenant and source; older ones are pruned when a new check is recorded. */
    public static final int MAX_KEPT = 100;

    public SourceConnectionCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid source instance");
        }
        if (actor == null || actor.isBlank() || actor.length() > 128 || actor.trim().length() != actor.length()) {
            throw new IllegalArgumentException("Invalid actor");
        }
        if (dataMode == null || !dataMode.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid data mode");
        }
        if (statusCode == null || !statusCode.matches("[ -~]{1,64}")) {
            throw new IllegalArgumentException("Invalid status code");
        }
        if (reportedVersion != null && !reportedVersion.matches("[ -~]{1,32}")) {
            throw new IllegalArgumentException("Invalid reported version");
        }
    }
}
