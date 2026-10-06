package com.acme.opsweave.integration.api;

import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fenced catalog and binding writes for one item scan. Every call presents the caller's durable
 * source lease: an implementation must refuse to store, activate or retire anything once that
 * lease is lost, superseded, expired or belongs to another scope. No connector, request or model
 * can obtain a lease here, and a refused call must leave the catalog unchanged.
 */
public interface SourceItemWritePort {
    /** Stores one mapped item pair inside the presented lease; a lost lease rolls the whole call back. */
    void upsert(SourceScan.Token scan, MetricDefinition definition, MetricBinding binding);

    /** Inactivates bindings whose item was absent from a finished scan, inside the same lease. */
    int retireMissing(SourceScan.Token scan, Set<String> observedExternalIds);

    /** Inactivates active bindings missing from a finished scan, limited to the captured host cohort. */
    int retireMissing(SourceScan.Token scan, Set<String> capturedHostExternalIds, Set<String> observedExternalIds);

    static void requireItemScan(SourceScan.Token scan) {
        Objects.requireNonNull(scan, "scan");
        if (!"item".equals(scan.scope().externalType())) {
            throw new IllegalArgumentException("Source item retirement requires an item scan");
        }
    }

    static Set<String> checkedExternalIds(Set<String> values, String name, boolean allowEmpty) {
        Objects.requireNonNull(values, name);
        Set<String> copy = new HashSet<>(values);
        if ((!allowEmpty && copy.isEmpty()) || copy.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return Set.copyOf(copy);
    }
}
