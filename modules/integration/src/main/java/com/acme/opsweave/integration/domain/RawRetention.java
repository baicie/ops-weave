package com.acme.opsweave.integration.domain;

/** Admission limits, not an eviction policy: existing Raw references must keep resolving. */
public final class RawRetention {
    public static final int MAX_PER_SOURCE = 1000;
    public static final int MAX_PER_TENANT = 5000;
    public static final int MAX_PAYLOAD_BYTES = 65_536;
    private RawRetention() {}

    public enum Reason { SOURCE_LIMIT, TENANT_LIMIT, PAYLOAD_LIMIT }

    /** Contains only a stable reason, never the rejected record or a storage exception. */
    public static final class Limit extends RuntimeException {
        private final Reason reason;
        public Limit(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    public record Policy(int maxPerSource, int maxPerTenant) {
        public Policy {
            if (maxPerSource < 1 || maxPerSource > MAX_PER_SOURCE
                || maxPerTenant < maxPerSource || maxPerTenant > MAX_PER_TENANT) {
                throw new IllegalArgumentException("Invalid Raw retention policy");
            }
        }
        public static Policy defaults() { return new Policy(MAX_PER_SOURCE, MAX_PER_TENANT); }
        public void admit(long sourceCount, long tenantCount) {
            if (sourceCount < 0 || tenantCount < sourceCount) throw new IllegalArgumentException("Invalid Raw counts");
            if (tenantCount >= maxPerTenant) throw new Limit(Reason.TENANT_LIMIT);
            if (sourceCount >= maxPerSource) throw new Limit(Reason.SOURCE_LIMIT);
        }
    }
}
