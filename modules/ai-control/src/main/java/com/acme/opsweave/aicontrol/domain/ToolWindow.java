package com.acme.opsweave.aicontrol.domain;

import java.time.*;

/** Sampling window, not a claim about historical knowledge availability. */
public record ToolWindow(Instant from, Instant to) {
    public ToolWindow {
        if (from == null || to == null || from.getEpochSecond() < 0 || to.getEpochSecond() > 9999999999L
            || from.getNano() != 0 || to.getNano() != 0 || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(1)) > 0)
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }
}
