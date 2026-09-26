package com.acme.opsweave.platform;

import com.acme.opsweave.integration.domain.ScanRunRetention;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsweave")
public record OpsweaveProperties(Auth auth, Zabbix zabbix, Inventory inventory) {
    public record Auth(String mode, boolean bindLoopbackOnly, Dev dev) {
        public record Dev(String token, String subject, String tenant, String permissions, String entityIds) {}
    }

    public record Zabbix(String mode, String url, String secretRef, String sourceInstanceId, int pageSize) {}

    public record Inventory(String store, String jdbcUrl, String jdbcUser, String jdbcPassword) {}

    /**
     * Optional trace-storage budget. Configuration may only tighten the published defaults; an
     * unusable value is rejected instead of silently widening what the platform keeps.
     */
    public ScanRunRetention.Policy scanRunRetention(Integer maxRunsPerScope, Integer maxRunsPerTenant) {
        return ScanRunRetention.Policy.of(maxRunsPerScope, maxRunsPerTenant);
    }
}
