package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector.SourceContext;
import com.acme.opsweave.integration.api.ZabbixHistoryPort;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Explicit synthetic values, never selected as a fallback for a failed live request. */
public final class FixtureZabbixHistoryReader implements ZabbixHistoryPort {
    @Override
    public HistoryPage read(SourceContext source, MetricBinding binding, HistoryWindow window) {
        if (!"20001".equals(binding.externalItemId())) {
            throw new HistoryReadException(HistoryReadException.Code.UNSUPPORTED_HISTORY);
        }
        Instant second = Instant.parse("2026-09-21T12:00:00Z");
        List<MetricPoint> rows = List.of(
            MetricPoint.normalize(second.plusNanos(100), new BigDecimal("25"), binding.valueTransform()),
            MetricPoint.normalize(second.plusNanos(200), new BigDecimal("30"), binding.valueTransform()),
            MetricPoint.normalize(second.plusSeconds(1), new BigDecimal("40"), binding.valueTransform())
        );
        return HistoryPage.select(window, rows.stream()
            .filter(point -> point.timestamp().getEpochSecond() >= window.fetchFrom()
                && point.timestamp().getEpochSecond() <= window.till()).toList());
    }
}
