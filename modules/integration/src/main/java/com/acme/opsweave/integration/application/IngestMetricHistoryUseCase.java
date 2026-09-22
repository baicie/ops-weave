package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure.Code;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Bounded, single-stream poll. External writes precede checkpoint commit; a lost acknowledgement replays. */
public final class IngestMetricHistoryUseCase {
    private final SourceReader source;
    private final MetricSink sink;
    private final CheckpointStore checkpoints;
    private final HistoryPollPolicy policy;
    private final Clock clock;

    public IngestMetricHistoryUseCase(SourceReader source, MetricSink sink, CheckpointStore checkpoints,
                                     HistoryPollPolicy policy, Clock clock) {
        this.source = source;
        this.sink = sink;
        this.checkpoints = checkpoints;
        this.policy = policy;
        this.clock = clock;
    }

    public Result poll(HistoryStream stream, long initialFrom) {
        return checkpoints.withLock(stream, initialFrom, checkpoint -> collect(stream, checkpoint));
    }

    private Update collect(HistoryStream stream, HistoryCheckpoint checkpoint) {
        var planned = policy.next(checkpoint, clock.instant().getEpochSecond());
        if (planned.isEmpty()) return new Update(checkpoint, Result.idle(checkpoint.completedThrough()));
        HistoryWindow window = planned.get();
        Slice snapshot = null;
        List<MetricPoint> points = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < policy.maxPages(); pageIndex++) {
            Slice slice = source.read(stream, window);
            if (!stream.tenantId().value().equals(slice.labels().get("tenant_id"))
                || !stream.sourceInstanceId().equals(slice.labels().get("source_instance_id"))
                || !stream.itemId().equals(slice.labels().get("external_item_id"))) throw new Failure(Code.INVALID_PAGE);
            if (snapshot == null) snapshot = slice;
            if (!snapshot.labels().equals(slice.labels()) || snapshot.definitionVersion() != slice.definitionVersion()
                || snapshot.bindingVersion() != slice.bindingVersion()) throw new Failure(Code.SERIES_CHANGED);
            validatePage(window, slice.page());
            points.addAll(slice.page().points());
            if (points.size() > MetricWriteBatch.MAX_POINTS) throw new Failure(Code.INVALID_POINTS);
            if (slice.page().windowComplete()) {
                MetricWriteBatch batch;
                try { batch = MetricWriteBatch.from(snapshot.labels(), points); }
                catch (IllegalArgumentException invalid) {
                    throw new Failure(switch (invalid.getMessage()) {
                        case "MILLISECOND_COLLISION" -> Code.MILLISECOND_COLLISION;
                        case "VALUE_PRECISION_LOSS" -> Code.VALUE_PRECISION_LOSS;
                        case "UNSUPPORTED_METRIC_LABEL" -> Code.UNSUPPORTED_METRIC_LABEL;
                        default -> Code.INVALID_POINTS;
                    });
                }
                String fingerprint = HistoryCheckpoint.fingerprint(batch.seriesHash(), source.checkpointIdentity(), sink.checkpointIdentity());
                if (!checkpoint.seriesHash().isEmpty() && !checkpoint.seriesHash().equals(fingerprint)) {
                    throw new Failure(Code.SERIES_CHANGED);
                }
                if (!batch.samples().isEmpty()) sink.write(batch);
                return new Update(checkpoint.accepted(window.till(), fingerprint),
                    new Result(true, window.from(), window.till(), points.size(), batch.samples().size(), batch.collapsedPoints()));
            }
            window = new HistoryWindow(window.from(), window.till(), slice.page().nextCursor(), window.limit());
        }
        throw new Failure(Code.PAGE_BUDGET_EXCEEDED);
    }

    private static void validatePage(HistoryWindow window, HistoryPage page) {
        HistoryCursor cursor = page.nextCursor();
        if (page.points().size() > window.limit() || cursor == null || cursor.clock() < window.from() || cursor.clock() > window.till()
            || (window.after() != null && cursor.compareTo(window.after()) <= 0)
            || (page.windowComplete() != cursor.equals(new HistoryCursor(window.till(), 999_999_999)))) {
            throw new Failure(Code.INVALID_PAGE);
        }
        Instant previous = window.after() == null ? null : window.after().instant();
        for (MetricPoint point : page.points()) {
            if (point.timestamp().getEpochSecond() < window.from() || point.timestamp().getEpochSecond() > window.till()
                || point.timestamp().isAfter(cursor.instant()) || (previous != null && !point.timestamp().isAfter(previous))) {
                throw new Failure(Code.INVALID_PAGE);
            }
            previous = point.timestamp();
        }
    }
}
