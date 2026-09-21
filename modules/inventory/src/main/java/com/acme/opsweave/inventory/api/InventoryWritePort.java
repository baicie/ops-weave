package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;

public interface InventoryWritePort {
    void upsert(Entity entity, Observation observation, ExternalLink link);
}
