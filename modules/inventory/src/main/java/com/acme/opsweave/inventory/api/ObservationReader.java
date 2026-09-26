package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.util.List;

public interface ObservationReader {
    /** Applies tenant, entity, time and source filters before limit + 1. */
    List<Entry> observations(TenantId tenant, EntityId entity, ObservationQuery query);
    record Entry(Observation observation, boolean exactTime) {}
}
