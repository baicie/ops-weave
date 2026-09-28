package com.acme.opsweave.integration.domain;

/** A verified identity walk permits reconciliation; mutable fields are not a database snapshot. */
public final class SyncScan {
    public static final String OFFSET_ATTEMPT = "offset-scan-attempt";
    /**
     * Compatibility label for a bounded host identity walk. The real connector pins a bounded,
     * sorted ID manifest digest and reads explicit ID batches; final membership is rechecked.
     */
    public static final String HOSTID_WATERMARK = "hostid-watermark-snapshot";
    /**
     * The same verified membership walk over item IDs, using the historical wire label.
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
