package com.acme.opsweave.inventory.domain;

/** Endpoint cardinality fixed by the published relation model revision. Zero means unlimited. */
public enum RelationCardinality {
    ONE_TO_ONE(1, 1), ONE_TO_MANY(1, 0), MANY_TO_MANY(0, 0);

    private final int maxActiveFrom;
    private final int maxActiveTo;

    RelationCardinality(int maxActiveFrom, int maxActiveTo) {
        this.maxActiveFrom = maxActiveFrom;
        this.maxActiveTo = maxActiveTo;
    }

    /** Maximum open-ended relations per starting endpoint, or 0 when unlimited. */
    public int maxActiveFrom() { return maxActiveFrom; }

    /** Maximum open-ended relations per ending endpoint, or 0 when unlimited. */
    public int maxActiveTo() { return maxActiveTo; }

    public static RelationCardinality fromModel(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid relation cardinality");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Invalid relation cardinality");
        }
    }
}
