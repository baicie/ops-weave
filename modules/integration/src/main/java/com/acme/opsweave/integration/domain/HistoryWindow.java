package com.acme.opsweave.integration.domain;

public record HistoryWindow(long from, long till, HistoryCursor after, int limit) {
    public HistoryWindow {
        new HistoryCursor(from, 0);
        new HistoryCursor(till, 0);
        if (till < from || till - from >= 3600 || limit < 1 || limit > 500
            || (after != null && (after.clock() < from || after.clock() > till))) {
            throw new IllegalArgumentException("History requires at most 3600 seconds and 1..500 points");
        }
    }

    public long fetchFrom() {
        return after == null ? from : after.clock() + (after.ns() == 999_999_999 ? 1 : 0);
    }
}
