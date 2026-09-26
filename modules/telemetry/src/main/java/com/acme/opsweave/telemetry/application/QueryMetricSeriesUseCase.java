package com.acme.opsweave.telemetry.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.telemetry.api.EntityExistence;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.api.MetricQueryPort;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import com.acme.opsweave.telemetry.domain.MetricType;
import java.time.Clock;
import java.util.Objects;

/** Authorized read of stored samples. Source sync permission is not required. */
public final class QueryMetricSeriesUseCase {
    public static final int FRESHNESS_SECONDS = 300;
    private final AuthorizationService authorization;
    private final EntityExistence entities;
    private final MetricDefinitionStore definitions;
    private final MetricQueryPort series;
    private final Clock clock;

    public QueryMetricSeriesUseCase(AuthorizationService authorization, EntityExistence entities,
                                   MetricDefinitionStore definitions, MetricQueryPort series, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.series = Objects.requireNonNull(series, "series");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result query(Principal principal, EntityId entityId, String metricKey, long from, long till, int maxPoints) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(entityId, "entityId");
        try {
            if (metricKey == null
                || authorization.authorize(principal, ResourceRef.entity(principal.tenantId(), entityId), Permission.ENTITY_READ).denied()
                || authorization.authorize(principal, ResourceRef.metric(principal.tenantId(), metricKey), Permission.METRIC_READ).denied()) {
                return metricKey == null ? Result.invalid() : Result.forbidden();
            }
        } catch (IllegalArgumentException invalid) {
            return Result.invalid();
        }
        if (!entities.exists(principal.tenantId(), entityId)) return Result.notFound("ENTITY_NOT_FOUND");
        MetricDefinition definition = definitions.find(principal.tenantId(), metricKey).orElse(null);
        if (definition == null) return Result.notFound("METRIC_NOT_FOUND");
        if (till > clock.instant().getEpochSecond() + 60) return Result.invalid();
        MetricSeriesQuery query;
        try {
            query = new MetricSeriesQuery(principal.tenantId(), entityId, metricKey, from, till, maxPoints, FRESHNESS_SECONDS);
        } catch (IllegalArgumentException invalid) {
            return Result.invalid();
        }
        try {
            var page = series.query(query);
            if (!page.entityId().equals(entityId) || !page.metricKey().equals(metricKey)
                || page.from() != from || page.till() != till
                || page.series().stream().anyMatch(row -> !row.unit().equals(definition.unit()))) {
                return Result.unavailable(MetricQueryException.Code.INVALID_RESPONSE);
            }
            if (definition.metricType() == MetricType.SUM) {
                // Counters get an explicit rate view with reset handling; gauges stay raw.
                page = page.withCounterRates();
            }
            return Result.available(definition.unit(), page);
        } catch (MetricQueryException failed) {
            return Result.unavailable(failed.code());
        }
    }

    public record Result(Kind kind, String error, String unit, MetricSeriesResult page) {
        public enum Kind { AVAILABLE, FORBIDDEN, NOT_FOUND, INVALID, UNAVAILABLE }

        public static Result available(String unit, MetricSeriesResult page) {
            return new Result(Kind.AVAILABLE, null, unit, page);
        }

        public static Result forbidden() { return new Result(Kind.FORBIDDEN, "FORBIDDEN", null, null); }
        public static Result notFound(String error) { return new Result(Kind.NOT_FOUND, error, null, null); }
        public static Result invalid() { return new Result(Kind.INVALID, "INVALID_METRIC_QUERY", null, null); }
        public static Result unavailable(MetricQueryException.Code code) {
            return new Result(Kind.UNAVAILABLE, code.name(), null, null);
        }
    }
}
