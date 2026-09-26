package com.acme.opsweave.integration.domain;

import com.acme.opsweave.alerting.domain.ExternalProblem;

/** Bounded read of problems active at any time in the inclusive UTC seconds window. */
public record ProblemReadWindow(long from, long till, String afterEventId, int limit) {
    public ProblemReadWindow {
        if (from < 0 || till < from || till > 9999999999L || till - from > 86400 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid problem read window");
        if (afterEventId != null) ExternalProblem.positiveId(afterEventId);
    }
}
