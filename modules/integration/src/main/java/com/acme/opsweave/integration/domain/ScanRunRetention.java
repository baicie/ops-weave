package com.acme.opsweave.integration.domain;

/**
 * Bounded lifecycle for stored scan-run records. Retention decides how long the trace can look
 * back; it never changes scan semantics, never retires an object and never turns a failed walk
 * into a snapshot. Without it, {@code integration.source_sync_run} grows for the lifetime of the
 * deployment and the "recent runs" read has no ceiling.
 *
 * <p>Two budgets apply at once: the newest {@code maxRunsPerScope} rows of one
 * tenant/source/object-type scope, and the newest {@code maxRunsPerTenant} rows of the whole
 * tenant. A run that is still {@code RUNNING} or that a pipeline version is pinned to is never
 * deleted: the pin is a foreign key, and dropping the row would either break the reference or
 * silently detach the mapping version the trace is supposed to explain.
 */
public final class ScanRunRetention {
    /** Defaults are the published policy; configuration may only tighten them. */
    public static final int DEFAULT_MAX_RUNS_PER_SCOPE = 1000;
    public static final int DEFAULT_MAX_RUNS_PER_TENANT = 5000;

    private ScanRunRetention() {}

    /** How many rows the trace keeps for one scope and for one tenant. */
    public record Policy(int maxRunsPerScope, int maxRunsPerTenant) {
        public Policy {
            if (maxRunsPerScope < 1 || maxRunsPerScope > DEFAULT_MAX_RUNS_PER_SCOPE
                || maxRunsPerTenant < 1 || maxRunsPerTenant > DEFAULT_MAX_RUNS_PER_TENANT
                || maxRunsPerTenant < maxRunsPerScope) {
                throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
            }
        }

        public static Policy defaults() {
            return new Policy(DEFAULT_MAX_RUNS_PER_SCOPE, DEFAULT_MAX_RUNS_PER_TENANT);
        }

        /**
         * Resolves an optional configured budget. {@code null} means "use the published default";
         * anything outside the published ceiling or internally inconsistent throws, so a bad
         * configuration fails at startup instead of silently widening what the platform keeps.
         */
        public static Policy of(Integer maxRunsPerScope, Integer maxRunsPerTenant) {
            Policy defaults = defaults();
            return new Policy(
                maxRunsPerScope == null ? defaults.maxRunsPerScope() : maxRunsPerScope,
                maxRunsPerTenant == null ? defaults.maxRunsPerTenant() : maxRunsPerTenant
            );
        }
    }

    /** What one sweep left behind. Both counts refer to the scope that was swept. */
    public record Sweep(int retained, int deleted) {
        public Sweep {
            if (retained < 0 || deleted < 0) {
                throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
            }
        }
    }
}
