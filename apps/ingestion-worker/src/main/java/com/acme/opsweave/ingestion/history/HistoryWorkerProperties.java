package com.acme.opsweave.ingestion.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsweave.history")
public record HistoryWorkerProperties(String platformUrl, String platformToken, String expectedDataMode,
    String tenant, String sourceInstanceId, String itemId, String streamName, long initialFrom,
    int stepSeconds, int overlapSeconds, int delaySeconds, int maxPages, long pollMillis,
    String victoriaUrl, String jdbcUrl, String jdbcUser, String jdbcPassword) {
    @Override public String toString() { return "HistoryWorkerProperties[credentials redacted]"; }
}
