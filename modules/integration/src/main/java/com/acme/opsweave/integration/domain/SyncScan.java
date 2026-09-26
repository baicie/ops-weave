package com.acme.opsweave.integration.domain;

/** An offset walk is one complete scan attempt, not a consistent database snapshot. */
public final class SyncScan {
    public static final String OFFSET_ATTEMPT = "offset-scan-attempt";
    /**
     * A hostid-watermark walk: the maximum hostid and the row count are captured before the first
     * request, pages are read ascending inside that watermark, and the walk is complete only when
     * the observed rows match the captured count. Requires a monotonically increasing hostid.
     */
    public static final String HOSTID_WATERMARK = "hostid-watermark-snapshot";
    /**
     * The same bounded walk over items: the highest itemid and the row count are captured before the
     * first request, and the walk is complete only when the observed rows match that snapshot.
     */
    public static final String ITEMID_WATERMARK = "itemid-watermark-snapshot";
    public static final String NONE = "none";

    /**
     * True only for a walk that proved its own bound. Reconciliation is allowed for these labels and
     * for nothing else: an offset attempt, a legacy row or a connector that cannot bound its walk must
     * never retire an object that it merely failed to observe.
     */
    public static boolean verified(String scanConsistency) {
        return HOSTID_WATERMARK.equals(scanConsistency) || ITEMID_WATERMARK.equals(scanConsistency);
    }

    private SyncScan() {}
}
