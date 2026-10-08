package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.platform.OpsweaveProperties;
import org.junit.jupiter.api.Test;

class LegacyZabbixCompatibilityTest {
    @Test
    void onlyExplicitFixtureModeKeepsLegacyBoundaryOpen() {
        var fixture = properties("fixture");
        assertTrue(LegacyZabbixCompatibility.fixtureOnly(fixture));
        assertTrue(LegacyZabbixCompatibility.fixtureOnly(properties(" fixture ")));
        assertFalse(LegacyZabbixCompatibility.fixtureOnly(properties("jsonrpc")));
        assertFalse(LegacyZabbixCompatibility.fixtureOnly(properties("real")));
        assertFalse(LegacyZabbixCompatibility.fixtureOnly(properties("closed")));
        var response = LegacyZabbixCompatibility.unavailable();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("fixture-only", response.getHeaders().getFirst("X-OpsWeave-Legacy"));
        assertEquals("source_unavailable", response.getBody().get("error"));
    }

    private static OpsweaveProperties properties(String mode) {
        return new OpsweaveProperties(
            new OpsweaveProperties.Auth("dev", true,
                new OpsweaveProperties.Auth.Dev("token", "subject", "tenant", "source.sync", "")),
            new OpsweaveProperties.Zabbix(mode, "http://127.0.0.1:1", "env:TEST", "zabbix-1", 100),
            new OpsweaveProperties.Inventory("memory", "", "", ""));
    }
}
