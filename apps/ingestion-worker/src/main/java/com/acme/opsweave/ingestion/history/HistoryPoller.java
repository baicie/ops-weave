package com.acme.opsweave.ingestion.history;

import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure;
import com.acme.opsweave.integration.application.IngestMetricHistoryUseCase;
import com.acme.opsweave.integration.domain.HistoryStream;
import com.acme.opsweave.sharedkernel.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class HistoryPoller {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryPoller.class);
    private final HistoryStream stream;
    private final long initialFrom;
    private final IngestMetricHistoryUseCase useCase;

    HistoryPoller(HistoryWorkerProperties p, IngestMetricHistoryUseCase useCase) {
        this.stream = new HistoryStream(new TenantId(p.tenant()), p.sourceInstanceId(), p.itemId(), p.streamName());
        this.initialFrom = p.initialFrom();
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${opsweave.history.poll-millis}", initialDelayString = "${opsweave.history.poll-millis}")
    public void poll() {
        try {
            var result = useCase.poll(stream, initialFrom);
            if (result.accepted()) LOG.info("History batch confirmed: stream={}, through={}, points={}, collapsed={}",
                stream.streamName(), result.till(), result.confirmedPoints(), result.collapsedPoints());
        } catch (Failure failed) {
            LOG.warn("History poll failed: stream={}, code={}; checkpoint unchanged; retry on next scheduled poll", stream.streamName(), failed.code());
        } catch (RuntimeException failed) {
            LOG.warn("History poll failed: stream={}, code=UNEXPECTED_FAILURE; no success reported", stream.streamName());
        }
    }
}
