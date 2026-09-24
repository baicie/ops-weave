package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.api.MetricQueryException.Code;
import com.acme.opsweave.telemetry.api.MetricQueryPort;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.MetricSeries;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.Sample;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads opsweave_metric_value through VictoriaMetrics export.
 * The selector is built only from the tenant, entity and metric key.
 */
public final class VictoriaMetricsQueryAdapter implements MetricQueryPort {
    private static final int MAX_SCANNED_POINTS = 8_000;
    private static final Set<String> IDENTITY = Set.of("__name__", "tenant_id", "source_instance_id", "external_item_id",
        "entity_id", "metric_key", "unit", "mapping_revision", "data_mode");
    private final URI origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public VictoriaMetricsQueryAdapter(URI origin) {
        if (!"http".equals(origin.getScheme()) || !Set.of("127.0.0.1", "[::1]", "::1").contains(origin.getHost())
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
            || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("Metric query endpoint must be an explicit loopback HTTP origin");
        }
        this.origin = origin;
    }

    @Override
    public MetricSeriesResult query(MetricSeriesQuery query) {
        String selector = "opsweave_metric_value{tenant_id=\"" + query.tenantId().value()
            + "\",entity_id=\"" + query.entityId().value()
            + "\",metric_key=\"" + query.metricKey() + "\"}";
        String path = "/api/v1/export?match%5B%5D=" + URLEncoder.encode(selector, StandardCharsets.UTF_8)
            + "&start=" + query.from() + "&end=" + (query.till() + 1) + "&reduce_mem_usage=1";
        HttpResponse<String> response;
        try {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(25)).GET().build();
            response = client.send(request, ignored -> new BoundedBody());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MetricQueryException(Code.SOURCE_UNAVAILABLE);
        } catch (IOException failed) {
            throw new MetricQueryException(tooLarge(failed) ? Code.INVALID_RESPONSE : Code.SOURCE_UNAVAILABLE);
        } catch (RuntimeException failed) {
            throw new MetricQueryException(Code.SOURCE_UNAVAILABLE);
        }
        if (response.statusCode() != 200) throw new MetricQueryException(Code.SOURCE_UNAVAILABLE);
        Map<SeriesKey, List<Sample>> grouped = new LinkedHashMap<>();
        int scanned = 0;
        boolean beyondCap = false;
        for (String line : response.body().split("\n")) {
            if (line.isBlank()) continue;
            JsonNode row;
            try { row = json.readTree(line); }
            catch (RuntimeException failed) { throw new MetricQueryException(Code.INVALID_RESPONSE); }
            Map<String, String> labels = labels(row);
            if (!"opsweave_metric_value".equals(labels.get("__name__"))
                || !query.tenantId().value().equals(labels.get("tenant_id"))
                || !query.entityId().value().toString().equals(labels.get("entity_id"))
                || !query.metricKey().equals(labels.get("metric_key"))) {
                throw new MetricQueryException(Code.INVALID_RESPONSE);
            }
            Map<String, String> dimensions = new TreeMap<>();
            labels.forEach((key, value) -> {
                if (key.startsWith("dimension_")) dimensions.put(key.substring("dimension_".length()), value);
                else if (!IDENTITY.contains(key)) throw new MetricQueryException(Code.INVALID_RESPONSE);
            });
            long revision;
            try { revision = Long.parseLong(requiredLabel(labels, "mapping_revision")); }
            catch (MetricQueryException invalid) { throw invalid; }
            catch (RuntimeException failed) { throw new MetricQueryException(Code.INVALID_RESPONSE); }
            var key = new SeriesKey(
                requiredLabel(labels, "source_instance_id"),
                requiredLabel(labels, "data_mode"),
                requiredLabel(labels, "external_item_id"),
                revision,
                requiredLabel(labels, "unit"),
                dimensions);
            JsonNode times = row.get("timestamps");
            JsonNode values = row.get("values");
            if (times == null || values == null || !times.isArray() || times.size() != values.size()) {
                throw new MetricQueryException(Code.INVALID_RESPONSE);
            }
            List<Sample> points = grouped.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (int i = 0; i < times.size(); i++) {
                if (scanned == MAX_SCANNED_POINTS) { beyondCap = true; break; }
                scanned++;
                if (!times.get(i).isIntegralNumber() || !times.get(i).canConvertToLong() || !values.get(i).isNumber()) {
                    throw new MetricQueryException(Code.INVALID_RESPONSE);
                }
                points.add(new Sample(times.get(i).asLong(), new BigDecimal(values.get(i).asString())));
            }
            if (beyondCap) break;
        }
        List<MetricSeries> series = new ArrayList<>();
        try {
            grouped.forEach((key, points) -> {
                if (!points.isEmpty()) series.add(new MetricSeries(key.sourceInstanceId, key.dataMode, key.externalItemId,
                    key.mappingRevision, key.unit, key.dimensions, points));
            });
        } catch (IllegalArgumentException invalid) {
            throw new MetricQueryException(Code.INVALID_RESPONSE);
        }
        return MetricSeriesResult.compose(query, series, beyondCap);
    }

    private static String requiredLabel(Map<String, String> labels, String key) {
        String value = labels.get(key);
        if (value == null || value.isBlank()) throw new MetricQueryException(Code.INVALID_RESPONSE);
        return value;
    }

    private static boolean tooLarge(Throwable failed) {
        while (failed != null) {
            if ("HTTP_RESPONSE_TOO_LARGE".equals(failed.getMessage())) return true;
            failed = failed.getCause();
        }
        return false;
    }

    private static Map<String, String> labels(JsonNode row) {
        JsonNode metric = row.get("metric");
        if (metric == null || !metric.isObject()) throw new MetricQueryException(Code.INVALID_RESPONSE);
        Map<String, String> labels = new LinkedHashMap<>();
        metric.properties().forEach(entry -> {
            if (!entry.getValue().isString()) throw new MetricQueryException(Code.INVALID_RESPONSE);
            labels.put(entry.getKey(), entry.getValue().asString());
        });
        return labels;
    }

    private record SeriesKey(String sourceInstanceId, String dataMode, String externalItemId, long mappingRevision,
                             String unit, Map<String, String> dimensions) {}

    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;

        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) bytes += buffer.remaining();
            if (bytes > 2 * 1024 * 1024) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("HTTP_RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { if (!failed) delegate.onError(failure); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
