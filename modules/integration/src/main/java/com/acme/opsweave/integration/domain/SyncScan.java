package com.acme.opsweave.integration.domain;

/** An offset walk is one complete scan attempt, not a consistent database snapshot. */
public final class SyncScan {
    public static final String OFFSET_ATTEMPT = "offset-scan-attempt";
    public static final String NONE = "none";

    private SyncScan() {}
}
