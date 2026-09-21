package com.acme.opsweave.platform.integration;

import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import org.springframework.stereotype.Component;

@Component
public final class EnvSecretSource implements ZabbixJsonRpcConnector.SecretSource {
    @Override
    public String resolve(String secretRef) {
        if (secretRef == null || !secretRef.startsWith("env:")) {
            throw new IllegalStateException("Only env: secret references are supported");
        }
        String name = secretRef.substring(4);
        if (!name.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalStateException("Invalid secret reference");
        }
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Zabbix secret is not configured");
        }
        return value;
    }
}
