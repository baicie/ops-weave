package com.acme.opsweave.integration.api;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.integration.domain.SyncScan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cursor is advanced only after durable acceptance, never after an in-memory fetch.
 *
 * <p>A page also states how the walk was bounded. {@link SyncScan#OFFSET_ATTEMPT} means the caller
 * only has an offset attempt; {@link SyncScan#HOSTID_WATERMARK} means the page belongs to a walk
 * bounded by a watermark captured before the first request, which is complete only when the
 * observed rows match that snapshot. A connector must never label a walk it cannot verify.
 */
public interface Connector {
    String type();
    ProbeResult probe(SourceContext source);
    Page fetch(SourceContext source, String cursor, int limit);

    record SourceContext(TenantId tenantId, String sourceInstanceId, String secretRef) {}
    /**
     * Result of one bounded read-only source probe. {@code statusCode} is a stable code, never vendor
     * text; {@code reportedVersion} is the version the source itself reports (or null when it reports
     * none), and it is a claim to review, not a verified support statement.
     */
    record ProbeResult(boolean reachable, String statusCode, String reportedVersion) {
        public ProbeResult {
            Objects.requireNonNull(statusCode, "statusCode");
            if (!statusCode.matches("[ -~]{1,64}")) {
                throw new IllegalArgumentException("Invalid probe status");
            }
            if (reportedVersion != null && !reportedVersion.matches("[ -~]{1,32}")) {
                throw new IllegalArgumentException("Invalid reported version");
            }
        }

        /** A probe without a reported version. */
        public ProbeResult(boolean reachable, String statusCode) {
            this(reachable, statusCode, null);
        }
    }
    record RawRecord(String externalId, Instant observedAt, Map<String, Object> payload) {}
    record Page(List<RawRecord> records, String nextCursor, boolean snapshotComplete, String scanConsistency) {
        public Page {
            Objects.requireNonNull(records, "records");
            scanConsistency = scanConsistency == null || scanConsistency.isBlank()
                ? SyncScan.OFFSET_ATTEMPT
                : scanConsistency;
        }

        /** An unbounded offset walk: the label describes the method, not a proven snapshot. */
        public Page(List<RawRecord> records, String nextCursor, boolean snapshotComplete) {
            this(records, nextCursor, snapshotComplete, SyncScan.OFFSET_ATTEMPT);
        }
    }
}
