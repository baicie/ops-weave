package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;

/** Selected when no Zabbix source is configured. Fetch fails closed instead of returning an empty snapshot. */
public final class ClosedZabbixConnector implements Connector {
    @Override
    public String type() {
        return "zabbix";
    }

    @Override
    public ProbeResult probe(SourceContext source) {
        return new ProbeResult(false, "not-configured");
    }

    @Override
    public Page fetch(SourceContext source, String cursor, int limit) {
        throw new IllegalStateException("Zabbix connector is closed; refusing empty snapshot");
    }
}
