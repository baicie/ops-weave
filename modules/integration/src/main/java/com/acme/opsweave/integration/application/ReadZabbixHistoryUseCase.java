package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.Connector.SourceContext;
import com.acme.opsweave.integration.api.ZabbixHistoryPort;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.time.Clock;
import java.util.concurrent.Semaphore;

public final class ReadZabbixHistoryUseCase {
    private final AuthorizationService authorization;
    private final MetricDefinitionStore metrics;
    private final ZabbixHistoryPort history;
    private final String sourceInstanceId;
    private final String secretRef;
    private final String dataMode;
    private final Clock clock;
    private final Semaphore concurrency = new Semaphore(4);

    public ReadZabbixHistoryUseCase(AuthorizationService authorization, MetricDefinitionStore metrics,
                                  ZabbixHistoryPort history, String sourceInstanceId, String secretRef,
                                  String dataMode, Clock clock) {
        this.authorization = authorization;
        this.metrics = metrics;
        this.history = history;
        this.sourceInstanceId = sourceInstanceId;
        this.secretRef = secretRef;
        this.dataMode = dataMode;
        this.clock = clock;
    }

    public Result execute(Principal principal, String itemId, HistoryWindow window) {
        if (!principal.has(Permission.METRIC_READ) || !principal.has(Permission.ENTITY_READ)
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), sourceInstanceId), Permission.SOURCE_SYNC).denied()) {
            return Result.failure(Kind.FORBIDDEN, "FORBIDDEN");
        }
        if (!itemId.matches("[1-9][0-9]{0,19}") || window.till() >= clock.instant().getEpochSecond()) {
            throw new IllegalArgumentException("History requires a numeric item id and a closed past window");
        }
        MetricBinding binding = metrics.findBinding(principal.tenantId(), sourceInstanceId, itemId).orElse(null);
        if (binding == null || binding.lifecycle() != MetricLifecycle.ACTIVE) {
            return Result.failure(Kind.NOT_FOUND, "BINDING_UNAVAILABLE");
        }
        if (authorization.authorize(principal, ResourceRef.entity(binding.tenantId(), binding.entityId()), Permission.ENTITY_READ).denied()
            || authorization.authorize(principal, ResourceRef.metric(binding.tenantId(), binding.metricKey()), Permission.METRIC_READ).denied()) {
            return Result.failure(Kind.NOT_FOUND, "BINDING_UNAVAILABLE");
        }
        MetricDefinition definition = metrics.find(principal.tenantId(), binding.metricKey()).orElse(null);
        if (definition == null || definition.metricType() == MetricType.HISTOGRAM
            || (definition.valueType() != MetricValueType.DOUBLE && definition.valueType() != MetricValueType.INTEGER)) {
            return Result.failure(Kind.UNAVAILABLE, "UNSUPPORTED_HISTORY");
        }
        if (!concurrency.tryAcquire()) return Result.failure(Kind.UNAVAILABLE, "HISTORY_BUSY");
        try {
            HistoryPage page = history.read(new SourceContext(principal.tenantId(), sourceInstanceId, secretRef), binding, window);
            return new Result(Kind.SUCCESS, null, binding, definition, page, dataMode);
        } catch (HistoryReadException failed) {
            return Result.failure(Kind.UNAVAILABLE, failed.code().name());
        } catch (RuntimeException failed) {
            return Result.failure(Kind.UNAVAILABLE, "SOURCE_FETCH_FAILED");
        } finally {
            concurrency.release();
        }
    }

    public enum Kind { SUCCESS, FORBIDDEN, NOT_FOUND, UNAVAILABLE }
    public record Result(Kind kind, String error, MetricBinding binding, MetricDefinition definition,
                         HistoryPage page, String dataMode) {
        static Result failure(Kind kind, String error) { return new Result(kind, error, null, null, null, null); }
    }
}
