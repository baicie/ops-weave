package com.acme.opsweave.integration.api;

import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
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
}
