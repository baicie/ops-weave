package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded operator-managed file, reread on every request; invalid/missing data never reuses old grants. */
public final class FileIdentityGrants {
    public record Bound(IdentityGrant grant, String digest) {}
    private final Path path;
    private final String issuer;
    public FileIdentityGrants(OidcSettings settings) { path = Path.of(settings.grantsFile()); issuer = settings.issuer(); read(); }
    public Bound find(String subject) {
        return read().stream().filter(row -> row.grant().externalSubject().equals(subject) && row.grant().enabled()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Identity is not authorized"));
    }
    private List<Bound> read() {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            byte[] bytes; try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(262145); }
            if (bytes.length == 0 || bytes.length > 262144) throw new IllegalArgumentException();
            var mapper = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            var root = mapper.readTree(bytes); exact(root, "schemaVersion", "grants");
            if (!text(root, "schemaVersion").equals("1.0") || !root.get("grants").isArray() || root.get("grants").size() > 500) throw new IllegalArgumentException();
            var result = new ArrayList<Bound>(); var subjects = new HashSet<String>(); var actors = new HashSet<String>();
            for (var row : root.get("grants")) {
                exact(row, "issuer", "externalSubject", "subjectId", "tenantId", "revision", "enabled", "permissions", "scope");
                if (!text(row, "issuer").equals(issuer) || !row.get("revision").isIntegralNumber() || !row.get("revision").canConvertToLong()
                    || !row.get("enabled").isBoolean() || !row.get("permissions").isArray() || row.get("permissions").size() > Permission.values().length) throw new IllegalArgumentException();
                var tenant = new TenantId(text(row, "tenantId")); var actor = new SubjectId(text(row, "subjectId"));
                var permissions = new HashSet<Permission>();
                for (var p : row.get("permissions")) {
                    if (!p.isString()) throw new IllegalArgumentException(); var permission = Permission.fromWire(p.asString());
                    if (!p.asString().equals(permission.wireValue()) || !permissions.add(permission)) throw new IllegalArgumentException();
                }
                var scope = row.get("scope"); exact(scope, "tenantWide", "resources");
                if (!scope.get("tenantWide").isBoolean() || !scope.get("resources").isArray() || scope.get("resources").size() > 2000) throw new IllegalArgumentException();
                var refs = new HashSet<ResourceRef>();
                for (var resource : scope.get("resources")) {
                    exact(resource, "type", "id"); var type = text(resource, "type"); var id = text(resource, "id");
                    if (!Set.of("entity", "incident", "metric", "source", "evidence", "ai-insight", "skill").contains(type)
                        || !id.matches("[A-Za-z0-9_.:/%*-]{1,128}") || !refs.add(new ResourceRef(tenant, type, id))) throw new IllegalArgumentException();
                }
                if (scope.get("tenantWide").asBoolean() && !refs.isEmpty()) throw new IllegalArgumentException();
                var grant = new IdentityGrant(issuer, text(row, "externalSubject"), row.get("revision").asLong(), row.get("enabled").asBoolean(),
                    new Principal(actor, tenant, permissions, scope.get("tenantWide").asBoolean() ? ResourceScope.tenantWide() : ResourceScope.of(refs)));
                if (!subjects.add(grant.externalSubject()) || !actors.add(tenant.value() + ":" + actor.value())) throw new IllegalArgumentException();
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(row)));
                result.add(new Bound(grant, digest));
            }
            return List.copyOf(result);
        } catch (Exception invalid) { throw new IllegalStateException("Identity authorization configuration unavailable"); }
    }
    static String text(JsonNode node, String name) { if (node == null || node.get(name) == null || !node.get(name).isString()) throw new IllegalArgumentException(); return node.get(name).asString(); }
    static void exact(JsonNode node, String... names) { if (node == null || !node.isObject() || node.size() != names.length || !Arrays.stream(names).allMatch(node::has)) throw new IllegalArgumentException(); }
}
