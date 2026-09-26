package com.acme.opsweave.integration.domain;

import java.util.List;
import java.util.Objects;

/** Bounded, read-only mapping report. Stored reports never carry Raw payloads. */
public record PipelineEvaluation(String mode, String purpose, boolean dryRun, boolean writesPerformed,
    String sourceInstanceId, String syncRunId, String sourceRunStatus, String dataMode,
    PipelineVersion.Ref originalVersion, PipelineVersion.Ref targetVersion, int fetched, long retained,
    long missingRaw, boolean truncated, int oversized, int accepted, int rejected, int changed,
    boolean wouldFailFast, List<Row> rows) {
    public PipelineEvaluation {
        Objects.requireNonNull(originalVersion); Objects.requireNonNull(targetVersion);
        rows = List.copyOf(rows);
        if (!dryRun || writesPerformed || rows.size() > 100 || fetched < 0 || retained < 0 || missingRaw < 0
            || oversized < 0 || accepted < 0 || rejected < 0 || changed < 0
            || accepted + rejected + oversized != rows.size() || changed > rows.size()) {
            throw new IllegalArgumentException("Invalid pipeline evaluation");
        }
    }
    public record Host(String entityId, String name, String ip, String lifecycle) {}
    public record Row(String rawRef, String status, Host previous, Host candidate, boolean changed) {}
}
