package com.acme.opsweave.ingestion.history;

import com.acme.opsweave.integration.api.HistoryIngestionPorts.MetricSink;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure.Code;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** Bounded non-streaming Influx write protocol; VM acknowledges parsing/acceptance, not cross-system exactly-once. */
public final class VictoriaMetricsWriter implements MetricSink {
    private final LoopbackHttp http;
    private final String checkpointIdentity;
    private final JsonMapper json = JsonMapper.builder().build();
    private final int verificationAttempts;
    private final long verificationPauseMillis;

    public VictoriaMetricsWriter(URI origin) { this(origin, 11, 2000); }

    VictoriaMetricsWriter(URI origin, int attempts, long pauseMillis) {
        if (attempts < 1 || attempts > 11 || pauseMillis < 0 || pauseMillis > 2000) throw new IllegalArgumentException("Invalid verification budget");
        http = new LoopbackHttp(origin); checkpointIdentity = origin.toASCIIString();
        verificationAttempts = attempts; verificationPauseMillis = pauseMillis;
    }

    @Override public String checkpointIdentity() { return checkpointIdentity; }

    public void verifyConfiguration() {
        try {
            var response = http.request("GET", "/flags", null, Map.of());
            if (response.statusCode() != 200) throw new Failure(Code.CONFIGURATION_INVALID);
            Map<String, String> flags = new HashMap<>();
            for (String line : response.body().split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0) flags.put(line.substring(0, equals).replaceFirst("^-", ""), line.substring(equals + 1).replace("\"", ""));
            }
            // /flags lists explicitly configured flags only. These are the pinned 1.152.0 defaults.
            if (!"1ms".equals(flags.get("dedup.minScrapeInterval")) || !"false".equals(flags.getOrDefault("influx.forceStreamMode", "false"))
                || !"false".equals(flags.getOrDefault("influxSkipSingleField", "false")) || !"false".equals(flags.getOrDefault("influxSkipMeasurement", "false"))
                || !"_".equals(flags.getOrDefault("influxMeasurementFieldSeparator", "_"))
                || !"1ms".equals(flags.getOrDefault("influxTrimTimestamp", "1ms"))) throw new Failure(Code.CONFIGURATION_INVALID);
        } catch (Failure known) { throw known; }
        catch (RuntimeException failed) { throw new Failure(Code.SINK_FAILED); }
    }

    @Override
    public void write(MetricWriteBatch batch) {
        if (batch.samples().isEmpty()) return;
        verifyConfiguration();
        Set<Long> present = visible(batch);
        if (present.size() == batch.samples().size()) return;
        StringBuilder series = new StringBuilder("opsweave_metric");
        new TreeMap<>(batch.labels()).forEach((key, value) -> series.append(',').append(key).append('=').append(value));
        StringBuilder body = new StringBuilder();
        for (var point : batch.samples()) {
            if (present.contains(point.timestampMillis())) continue;
            body.append(series).append(" value=").append(point.value().toPlainString()).append(' ').append(point.timestampMillis()).append('\n');
        }
        if (body.length() > 1024 * 1024) throw new Failure(Code.INVALID_POINTS);
        try {
            var response = http.request("POST", "/write?precision=ms", body.toString(), Map.of("Content-Type", "text/plain; charset=utf-8", "Stream-Mode", "0"));
            if (response.statusCode() != 204) throw new Failure(Code.SINK_FAILED);
        } catch (RuntimeException failed) { throw new Failure(Code.SINK_FAILED); }
        // Poll visibility only: never silently retry the POST. A lost acknowledgement is retried next poll.
        for (int attempt = 0; attempt < verificationAttempts; attempt++) {
            if (visible(batch).size() == batch.samples().size()) return;
            if (attempt + 1 < verificationAttempts) {
                try { Thread.sleep(verificationPauseMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure(Code.SINK_UNCONFIRMED); }
            }
        }
        throw new Failure(Code.SINK_UNCONFIRMED);
    }

    private Set<Long> visible(MetricWriteBatch batch) {
        StringBuilder selector = new StringBuilder("opsweave_metric_value{");
        new TreeMap<>(batch.labels()).forEach((key, value) -> selector.append(key).append("=\"").append(value).append("\","));
        selector.setLength(selector.length() - 1);
        selector.append('}');
        String query = "/api/v1/export?match%5B%5D=" + URLEncoder.encode(selector.toString(), StandardCharsets.UTF_8)
            // Whole-second bounds avoid floating-point parsing rounding the final millisecond out of the query.
            + "&start=" + batch.samples().getFirst().timestampMillis() / 1000
            + "&end=" + (batch.samples().getLast().timestampMillis() / 1000 + 1)
            + "&reduce_mem_usage=1";
        try {
            var response = http.request("GET", query, null, Map.of());
            if (response.statusCode() != 200) throw new Failure(Code.SINK_UNCONFIRMED);
            Map<Long, BigDecimal> expected = new HashMap<>();
            batch.samples().forEach(point -> expected.put(point.timestampMillis(), point.value()));
            Set<Long> found = new HashSet<>();
            Map<String, String> expectedLabels = new HashMap<>(batch.labels());
            expectedLabels.put("__name__", "opsweave_metric_value");
            int scanned = 0;
            for (String line : response.body().split("\n")) {
                if (line.isBlank()) continue;
                var row = json.readTree(line);
                Map<String, String> actualLabels = new HashMap<>();
                if (!row.path("metric").isObject()) throw new Failure(Code.SINK_UNCONFIRMED);
                row.get("metric").properties().forEach(entry -> actualLabels.put(entry.getKey(), entry.getValue().asString()));
                if (!expectedLabels.equals(actualLabels)) throw new Failure(Code.SINK_UNCONFIRMED);
                var times = row.get("timestamps");
                var values = row.get("values");
                if (times == null || values == null || !times.isArray() || !values.isArray() || times.size() != values.size()) {
                    throw new Failure(Code.SINK_UNCONFIRMED);
                }
                scanned += times.size();
                if (scanned > 16000) throw new Failure(Code.SINK_UNCONFIRMED);
                for (int i = 0; i < times.size(); i++) {
                    if (!times.get(i).isIntegralNumber() || !times.get(i).canConvertToLong() || !values.get(i).isNumber()) {
                        throw new Failure(Code.SINK_UNCONFIRMED);
                    }
                    long time = times.get(i).asLong();
                    if (expected.containsKey(time)) {
                        if (new BigDecimal(values.get(i).asString()).compareTo(expected.get(time)) != 0) {
                            throw new Failure(Code.STORED_VALUE_CONFLICT);
                        }
                        found.add(time);
                    }
                }
            }
            return found;
        } catch (Failure known) { throw known; }
        catch (RuntimeException failed) { throw new Failure(Code.SINK_UNCONFIRMED); }
    }
}
