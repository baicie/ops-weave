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
    private final java.util.List<Target> targets;
    private final IngestMetricHistoryUseCase useCase;

    HistoryPoller(HistoryWorkerProperties p, IngestMetricHistoryUseCase useCase, String configuredStreams) {
        this.targets = targets(p, configuredStreams);
        this.useCase = useCase;
    }

    record Target(HistoryStream stream, long initialFrom) {
        Target { com.acme.opsweave.integration.domain.HistoryCheckpoint.initial(initialFrom); }
    }

    static java.util.List<Target> targets(HistoryWorkerProperties p, String configuredStreams) {
        if (configuredStreams == null || configuredStreams.isBlank())
            return java.util.List.of(new Target(new HistoryStream(new TenantId(p.tenant()), p.sourceInstanceId(), p.itemId(), p.streamName()), p.initialFrom()));
        if (p.itemId() != null && !p.itemId().isBlank()) throw new IllegalArgumentException("Configure item-id or streams, not both");
        if (configuredStreams.length() > 800) throw new IllegalArgumentException("History stream configuration exceeds budget");
        var entries = configuredStreams.split(",", -1);
        if (entries.length > 8) throw new IllegalArgumentException("History worker requires 1 to 8 streams");
        var result = new java.util.ArrayList<Target>();
        for (String entry : entries) {
            var parts = entry.trim().split(":", -1);
            if (parts.length < 2 || parts.length > 3 || (parts.length == 3 && !parts[2].matches("0|[1-9][0-9]{0,9}")))
                throw new IllegalArgumentException("History stream requires itemId:streamName[:initialFrom]");
            result.add(new Target(new HistoryStream(new TenantId(p.tenant()), p.sourceInstanceId(), parts[0], parts[1]),
                parts.length == 3 ? Long.parseLong(parts[2]) : p.initialFrom()));
        }
        if (result.stream().map(target -> target.stream().itemId()).distinct().count() != result.size())
            throw new IllegalArgumentException("History streams require unique item IDs");
        return java.util.List.copyOf(result);
    }

    @Scheduled(fixedDelayString = "${opsweave.history.poll-millis}", initialDelayString = "${opsweave.history.poll-millis}")
    public void poll() {
        // One scheduler, bounded serial streams, and the existing per-stream page/time budgets.
        for (Target target : targets) poll(target);
    }

    private void poll(Target target) {
        var stream = target.stream();
        try {
            var result = useCase.poll(stream, target.initialFrom());
            if (result.accepted()) LOG.info("History batch confirmed: stream={}, through={}, points={}, collapsed={}",
                stream.streamName(), result.till(), result.confirmedPoints(), result.collapsedPoints());
        } catch (Failure failed) {
            LOG.warn("History poll failed: stream={}, code={}; checkpoint unchanged; retry on next scheduled poll", stream.streamName(), failed.code());
        } catch (RuntimeException failed) {
            LOG.warn("History poll failed: stream={}, code=UNEXPECTED_FAILURE; no success reported", stream.streamName());
        }
    }
}
