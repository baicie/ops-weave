package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.telemetry.domain.MetricBinding;

/** Bounded source reading only; returning a cursor never commits a durable ingestion checkpoint. */
public interface ZabbixHistoryPort {
    HistoryPage read(Connector.SourceContext source, MetricBinding binding, HistoryWindow window);
}
