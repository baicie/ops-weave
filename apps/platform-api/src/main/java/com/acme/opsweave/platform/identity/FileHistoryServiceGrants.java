package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.HistoryServiceGrant;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No grant cache: revocation or invalid configuration applies to the next request. */
public final class FileHistoryServiceGrants {
    private final HistoryServiceSettings settings;
    public FileHistoryServiceGrants(HistoryServiceSettings settings) { this.settings = settings; read(); }
    public HistoryServiceGrant find(String clientId) {
        return read().stream().filter(grant -> grant.clientId().equals(clientId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Service grant unavailable"));
    }
    private List<HistoryServiceGrant> read() {
        try {
            var path = Path.of(settings.grantsFile()); if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            byte[] bytes; try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(262145); }
            if (bytes.length == 0 || bytes.length > 262144) throw new IllegalArgumentException();
            var root = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
            FileIdentityGrants.exact(root, "schemaVersion", "grants");
            if (!text(root, "schemaVersion").equals("1.0") || !root.get("grants").isArray() || root.get("grants").size() > 500) throw new IllegalArgumentException();
            var result = new ArrayList<HistoryServiceGrant>(); var clients = new HashSet<String>(); var actors = new HashSet<String>();
            for (var row : root.get("grants")) {
                FileIdentityGrants.exact(row, "issuer", "clientId", "externalSubject", "subjectId", "tenantId", "revision", "enabled", "sourceInstanceId", "itemIds", "entityIds", "metricKeys", "validFrom", "validUntil", "from", "till", "maxWindowSeconds", "maxPoints", "requestsPerMinute");
                if (!text(row, "issuer").equals(settings.issuer()) || !row.get("enabled").isBoolean()) throw new IllegalArgumentException();
                var tenant = new TenantId(text(row, "tenantId")); var actor = new SubjectId(text(row, "subjectId")); String source = text(row, "sourceInstanceId");
                var refs = new HashSet<ResourceRef>(); refs.add(ResourceRef.source(tenant, source));
                for (String id : strings(row, "entityIds", 500)) {
                    if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); refs.add(new ResourceRef(tenant, "entity", id));
                }
                for (String key : strings(row, "metricKeys", 100)) {
                    if (!key.matches("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException(); refs.add(ResourceRef.metric(tenant, key));
                }
                var principal = new Principal(actor, tenant, Set.of(Permission.ENTITY_READ, Permission.METRIC_READ, Permission.SOURCE_SYNC), ResourceScope.of(refs));
                var identity = new IdentityGrant(settings.issuer(), text(row, "externalSubject"), number(row, "revision"), row.get("enabled").asBoolean(), principal);
                var grant = new HistoryServiceGrant(identity, text(row, "clientId"), source, strings(row, "itemIds", 100), Instant.parse(text(row, "validFrom")), Instant.parse(text(row, "validUntil")),
                    number(row, "from"), number(row, "till"), Math.toIntExact(number(row, "maxWindowSeconds")), Math.toIntExact(number(row, "maxPoints")), Math.toIntExact(number(row, "requestsPerMinute")));
                if (!clients.add(grant.clientId()) || !actors.add(tenant.value() + ":" + actor.value())) throw new IllegalArgumentException();
                result.add(grant);
            }
            return List.copyOf(result);
        } catch (Exception invalid) { throw new IllegalStateException("History service authorization configuration unavailable"); }
    }
    private static Set<String> strings(JsonNode node, String field, int max) {
        var values = node.get(field); if (!values.isArray() || values.isEmpty() || values.size() > max) throw new IllegalArgumentException();
        var result = new HashSet<String>(); for (var value : values) if (!value.isString() || !result.add(value.asString())) throw new IllegalArgumentException(); return Set.copyOf(result);
    }
    static long number(JsonNode node, String field) { var value = node.get(field); if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException(); return value.asLong(); }
    private static String text(JsonNode node, String field) { return FileIdentityGrants.text(node, field); }
}
