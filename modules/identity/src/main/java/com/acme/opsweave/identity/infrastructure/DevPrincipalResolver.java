package com.acme.opsweave.identity.infrastructure;

import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit local development adapter. Not production authentication.
 * Requires a caller-supplied token of at least 32 characters; never invents a principal.
 */
public final class DevPrincipalResolver implements PrincipalResolver {
    private final byte[] expectedToken;
    private final Principal principal;

    public DevPrincipalResolver(
        String expectedToken,
        SubjectId subjectId,
        TenantId tenantId,
        Set<Permission> permissions,
        ResourceScope resourceScope
    ) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        if (expectedToken.length() < 32 || expectedToken.length() > 4096) {
            throw new IllegalArgumentException("Dev token must be between 32 and 4096 characters");
        }
        if (!expectedToken.equals(expectedToken.trim()) || expectedToken.toLowerCase(Locale.ROOT).contains("replace")) {
            throw new IllegalArgumentException("Dev token is not an explicit local secret");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.principal = new Principal(subjectId, tenantId, permissions, resourceScope);
    }

    @Override
    public Optional<Principal> resolve(BearerCredentials credentials) {
        byte[] provided = credentials.token().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, provided)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }
}
