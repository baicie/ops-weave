package com.acme.opsweave.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsweave")
public record OpsweaveProperties(Auth auth, Zabbix zabbix) {
    public record Auth(String mode, boolean bindLoopbackOnly, Dev dev) {
        public record Dev(String token, String subject, String tenant, String permissions, String entityIds) {}
    }

    public record Zabbix(String mode, String url, String secretRef, String sourceInstanceId) {}
}
