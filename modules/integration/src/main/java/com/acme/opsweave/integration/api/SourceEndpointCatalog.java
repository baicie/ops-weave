package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.SourceEndpoint;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;

/** Trusted deployment catalog. Implementations must not accept request-supplied destinations. */
public interface SourceEndpointCatalog {
    List<SourceEndpoint> list(TenantId tenant);
    Optional<SourceEndpoint> find(TenantId tenant,String id);
}
