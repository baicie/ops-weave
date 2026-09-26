package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.AssetIdentity;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** Identity mutations serialize with entity ingestion/review, bump its version and retain immutable actor-bound receipts. */
public interface AssetIdentityStore {
    record Receipt(AssetIdentity.Command command, AssetIdentity identity, long entityVersion) {
        public Receipt { Objects.requireNonNull(command); Objects.requireNonNull(identity); if(entityVersion < 2) throw new IllegalArgumentException("Invalid resulting entity version"); }
    }
    record Match(AssetIdentity identity, long entityVersion) {
        public Match { Objects.requireNonNull(identity); if(!identity.active() || entityVersion < 1) throw new IllegalArgumentException("Invalid identity match"); }
    }
    Receipt change(TenantId tenant, EntityId entity, String namespace, AssetIdentity.Command command, Instant now);
    List<AssetIdentity> identities(TenantId tenant, EntityId entity, String namespace, UUID after, int limit);
    Optional<Match> resolve(TenantId tenant, String namespace, String value);
}
