package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.identity.infrastructure.OidcPrincipalResolver;
import org.junit.jupiter.api.Test;

class OidcAdapterBoundaryTest {
    @Test
    void oidcModeDoesNotAcceptDevelopmentBearer() {
        var properties = new OpsweaveProperties(
            new OpsweaveProperties.Auth(
                "oidc",
                true,
                new OpsweaveProperties.Auth.Dev(
                    "test-dev-token-please-do-not-use-elsewhere",
                    "user-demo",
                    "tenant-demo",
                    "entity.read",
                    ""
                )
            ),
            new OpsweaveProperties.Zabbix("closed", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 100),
            new OpsweaveProperties.Inventory("memory", "", "", "")
        );
        var resolver = new PlatformConfiguration().principalResolver(properties);
        assertTrue(resolver.resolve(new com.acme.opsweave.identity.api.BearerCredentials("test-dev-token-please-do-not-use-elsewhere")).isEmpty());
    }

    @Test
    void oidcResolverDoesNotReturnMockPrincipal() {
        assertThrows(
            UnsupportedOperationException.class,
            () -> new OidcPrincipalResolver().resolve(new com.acme.opsweave.identity.api.BearerCredentials(
                "test-dev-token-please-do-not-use-elsewhere"
            ))
        );
    }
}
