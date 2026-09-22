package com.acme.opsweave.integration.domain;

import java.util.Optional;

public record HistoryPollPolicy(int stepSeconds, int overlapSeconds, int delaySeconds, int maxPages) {
    public HistoryPollPolicy {
        if (stepSeconds < 1 || overlapSeconds < 0 || stepSeconds + (long) overlapSeconds > 3600
            || delaySeconds < 1 || delaySeconds > 3600 || maxPages < 1 || maxPages > 8) {
            throw new IllegalArgumentException("Invalid history poll budget");
        }
    }

    public Optional<HistoryWindow> next(HistoryCheckpoint checkpoint, long nowSeconds) {
        long safeTill = nowSeconds - delaySeconds - 1;
        if (safeTill < checkpoint.initialFrom() || safeTill < checkpoint.completedThrough()) return Optional.empty();
        long from = Math.max(checkpoint.initialFrom(), checkpoint.completedThrough() - overlapSeconds + 1);
        long till = Math.min(safeTill, checkpoint.completedThrough() + stepSeconds);
        if (from > till) return Optional.empty();
        return Optional.of(new HistoryWindow(from, till, null, 500));
    }
}
