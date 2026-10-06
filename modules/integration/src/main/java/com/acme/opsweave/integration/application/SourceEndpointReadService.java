package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.identity.domain.Principal;
import java.util.Objects;

/** Trusted connector boundary: verify both immutable pins before and after bounded read-only IO. */
public final class SourceEndpointReadService {
    @FunctionalInterface public interface Operation<T> {T read(SourceEndpoint endpoint,char[] secret);}
    private final SourceEndpointService endpoints;private final SourceCredentialService credentials;
    public SourceEndpointReadService(SourceEndpointService endpoints,SourceCredentialService credentials){this.endpoints=Objects.requireNonNull(endpoints);this.credentials=Objects.requireNonNull(credentials);}
    public <T> T read(Principal p,SourceEndpoint.Pin endpointPin,SourceCredential.Pin credentialPin,Operation<T> trustedOperation){
        Objects.requireNonNull(trustedOperation);var endpoint=endpoints.requireReadPin(p,endpointPin);
        return credentials.withSecret(p,credentialPin,secret->{
            // Key opening may take time; recheck the destination immediately before dispatch.
            endpoints.requireReadPin(p,endpointPin);
            var result=trustedOperation.read(endpoint,secret);
            endpoints.requireReadPin(p,endpointPin);
            return result;
        });
    }
}
