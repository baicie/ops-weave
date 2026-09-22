package com.acme.opsweave.telemetry.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.List;
import java.util.Objects;

public final class ListMetricDefinitionsUseCase {
    private final AuthorizationService authorization;
    private final MetricDefinitionStore definitions;

    public ListMetricDefinitionsUseCase(AuthorizationService authorization, MetricDefinitionStore definitions) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
    }

    public ListResult list(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        AuthorizationDecision decision = authorization.authorize(
            principal,
            ResourceRef.anyMetric(principal.tenantId()),
            Permission.METRIC_READ
        );
        if (decision.denied()) {
            return ListResult.forbidden();
        }
        return ListResult.visible(definitions.list(principal.tenantId()));
    }

    public record ListResult(Kind kind, List<MetricDefinition> definitions) {
        public enum Kind { VISIBLE, FORBIDDEN }

        public static ListResult forbidden() {
            return new ListResult(Kind.FORBIDDEN, List.of());
        }

        public static ListResult visible(List<MetricDefinition> definitions) {
            return new ListResult(Kind.VISIBLE, List.copyOf(definitions));
        }
    }
}
