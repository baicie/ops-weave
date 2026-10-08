package com.acme.opsweave.platform.integration;

import com.acme.opsweave.platform.OpsweaveProperties;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Boundary for the pre-registration Zabbix endpoints. They are retained only for the
 * explicitly labelled local fixture; real sources must use a registered connection.
 */
final class LegacyZabbixCompatibility {
    private LegacyZabbixCompatibility() {}

    static boolean fixtureOnly(OpsweaveProperties properties) {
        return properties != null
            && properties.zabbix() != null
            && properties.zabbix().mode() != null
            && "fixture".equalsIgnoreCase(properties.zabbix().mode().trim());
    }

    static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-OpsWeave-Legacy", "fixture-only")
            .body(Map.of("error", "source_unavailable"));
    }
}
