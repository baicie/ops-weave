package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.SourceCredential;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;

/** Trusted cryptographic adapter; never supplied by a request or model. */
public interface CredentialProtector {
    record Scope(TenantId tenant, String owner, SourceCredential.Pin pin) {
        public Scope { java.util.Objects.requireNonNull(tenant); java.util.Objects.requireNonNull(owner); java.util.Objects.requireNonNull(pin); }
    }
    boolean available();
    String activeKeyId();
    default boolean canOpen(String keyId){return available()&&keyId.equals(activeKeyId());}
    SourceCredential.Envelope seal(Scope scope, char[] secret);
    char[] open(Scope scope, SourceCredential.Envelope envelope);
    String authenticate(String keyId, List<String> commandParts, char[] secret);
}
