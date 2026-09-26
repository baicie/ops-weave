package com.acme.opsweave.integration.domain;

import com.acme.opsweave.identity.domain.*;
import java.time.*;
import java.util.*;

/** Operator-owned service authorization, separate from browser identities and token claims. */
public record HistoryServiceGrant(IdentityGrant identity, String clientId, String sourceInstanceId, Set<String> itemIds,
        Instant validFrom, Instant validUntil, long from, long till, int maxWindowSeconds, int maxPoints, int requestsPerMinute) {
    public HistoryServiceGrant {
        Objects.requireNonNull(identity); Objects.requireNonNull(validFrom); Objects.requireNonNull(validUntil);
        itemIds = Set.copyOf(itemIds);
        if (clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,128}") || sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
            || itemIds.isEmpty() || itemIds.size() > 100 || itemIds.stream().anyMatch(id -> !id.matches("[1-9][0-9]{0,19}"))
            || !validFrom.isBefore(validUntil) || Duration.between(validFrom, validUntil).compareTo(Duration.ofDays(90)) > 0
            || from < 0 || till < from || till > 9_999_999_999L || till - from > 31L * 86400
            || maxWindowSeconds < 1 || maxWindowSeconds > 3600 || maxPoints < 1 || maxPoints > 500 || requestsPerMinute < 1 || requestsPerMinute > 120)
            throw new IllegalArgumentException("Invalid history service grant");
        var principal = identity.principal(); var scope = principal.resourceScope();
        if (!principal.permissions().equals(Set.of(Permission.ENTITY_READ, Permission.METRIC_READ, Permission.SOURCE_SYNC)) || scope.isTenantWide()
            || !scope.includes(ResourceRef.source(principal.tenantId(), sourceInstanceId))
            || scope.allowedResources().stream().noneMatch(r -> r.type().equals("entity"))
            || scope.allowedResources().stream().noneMatch(r -> r.type().equals("metric"))
            || scope.allowedResources().stream().anyMatch(r -> !r.tenantId().equals(principal.tenantId()) || r.id().equals("*")
                || !Set.of("entity", "metric", "source").contains(r.type()) || (r.type().equals("source") && !r.id().equals(sourceInstanceId))))
            throw new IllegalArgumentException("History service requires explicit resource scope");
    }
    public Principal authorize(String issuer, String client, String subject, Instant tokenExpires, Instant now,
            String configuredSource, String item, long requestedFrom, long requestedTill, int points) {
        var principal = identity.bind(issuer, subject, tokenExpires, now);
        if (!clientId.equals(client) || now.isBefore(validFrom) || !now.isBefore(validUntil) || !sourceInstanceId.equals(configuredSource)
            || !itemIds.contains(item) || requestedFrom < from || requestedTill > till || requestedTill < requestedFrom
            || requestedTill >= now.getEpochSecond() || requestedTill - requestedFrom >= maxWindowSeconds || points < 1 || points > maxPoints)
            throw new IllegalArgumentException("History service scope denied");
        return principal;
    }
}
