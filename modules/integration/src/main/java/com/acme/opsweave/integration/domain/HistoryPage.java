package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.time.Instant;
import java.util.List;

public record HistoryPage(List<MetricPoint> points, HistoryCursor nextCursor, boolean windowComplete) {
    public static final int SOURCE_LIMIT = 501;

    public HistoryPage {
        points = List.copyOf(points);
    }

    /** The source must return ascending clock/ns, without offset, up to SOURCE_LIMIT rows. */
    public static HistoryPage select(HistoryWindow window, List<MetricPoint> rows) {
        if (rows.size() > SOURCE_LIMIT) {
            throw new HistoryReadException(HistoryReadException.Code.INVALID_SOURCE_RESPONSE);
        }
        Instant previous = null;
        for (MetricPoint row : rows) {
            Instant time = row.timestamp();
            if (time.getEpochSecond() < window.fetchFrom() || time.getEpochSecond() > window.till()
                || (previous != null && !time.isAfter(previous))) {
                throw new HistoryReadException(HistoryReadException.Code.INVALID_SOURCE_RESPONSE);
            }
            previous = time;
        }
        long scannedThrough = window.till();
        if (rows.size() == SOURCE_LIMIT) {
            long boundarySecond = rows.getLast().timestamp().getEpochSecond();
            if (boundarySecond == window.fetchFrom()) {
                throw new HistoryReadException(HistoryReadException.Code.HISTORY_SECOND_LIMIT);
            }
            // The last second may be truncated. Replay that entire second on the next request.
            scannedThrough = boundarySecond - 1;
        }
        long safeSecond = scannedThrough;
        List<MetricPoint> eligible = rows.stream()
            .filter(point -> point.timestamp().getEpochSecond() <= safeSecond)
            .filter(point -> window.after() == null || point.timestamp().isAfter(window.after().instant()))
            .toList();
        if (eligible.size() > window.limit()) {
            List<MetricPoint> points = eligible.subList(0, window.limit());
            Instant last = points.getLast().timestamp();
            return new HistoryPage(points, new HistoryCursor(last.getEpochSecond(), last.getNano()), false);
        }
        return new HistoryPage(eligible, new HistoryCursor(scannedThrough, 999_999_999), scannedThrough == window.till());
    }
}
