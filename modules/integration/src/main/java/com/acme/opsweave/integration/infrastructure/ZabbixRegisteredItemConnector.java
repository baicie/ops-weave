package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.RegisteredItemConnector;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.SyncScan;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Item discovery pinned to a non-empty, group-filtered host manifest. */
public final class ZabbixRegisteredItemConnector implements RegisteredItemConnector {
    private static final int MAX_PAGE_SIZE = 500;
    private static final String CURSOR_PREFIX = "registered-items-v1";

    private final URI endpoint;
    private final ZabbixJsonRpcConnector.Transport transport;
    private final ZabbixJsonRpcConnector.SecretSource secrets;
    private final List<String> hostGroupIds;

    public ZabbixRegisteredItemConnector(
        URI endpoint,
        ZabbixJsonRpcConnector.Transport transport,
        ZabbixJsonRpcConnector.SecretSource secrets,
        List<String> hostGroupIds
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.hostGroupIds = SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        if (this.hostGroupIds.isEmpty()) throw new IllegalArgumentException("Registered item scan requires host groups");
    }

    @Override
    public String type() {
        return "zabbix";
    }

    @Override
    public ProbeResult probe(SourceContext source) {
        try {
            secrets.resolve(source.secretRef());
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"apiinfo.version\",\"params\":{},\"id\":1}";
            return new ProbeResult(true, "ok", transport.readText(transport.exchange(endpoint, body, null)));
        } catch (RuntimeException failed) {
            return new ProbeResult(false, "unreachable");
        }
    }

    @Override
    public ScopedPage fetchScoped(SourceContext source, String cursor, int limit) {
        var position = Cursor.decode(cursor);
        String token = secrets.resolve(source.secretRef());
        Manifest manifest;
        try {
            manifest = capture(token);
        } catch (IllegalStateException unstableManifest) {
            if (position != null) return incomplete();
            throw unstableManifest;
        }
        if (manifest.hostIds().isEmpty() || manifest.items().isEmpty()) return incomplete();
        if (position != null && !position.matches(manifest)) return incomplete();

        int start = position == null ? 0 : position.position();
        int end = Math.min(start + Math.min(Math.max(limit, 1), MAX_PAGE_SIZE), manifest.items().size());
        List<ItemIdentity> selected = manifest.items().subList(start, end);
        List<Map<String, Object>> rows = List.of();
        if (!selected.isEmpty()) {
            rows = readMetadata(token, manifest, selected);
            if (!validRows(rows, manifest, selected)) return incomplete();
        }

        Manifest afterPage;
        try {
            afterPage = capture(token);
        } catch (IllegalStateException unstableManifest) {
            return incomplete();
        }
        if (!manifest.equals(afterPage)) return incomplete();

        boolean complete = end == manifest.items().size();
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            records.add(new RawRecord(String.valueOf(row.get("itemid")), observedAt, row));
        }
        Page page = new Page(
            List.copyOf(records),
            complete ? null : manifest.cursor(end),
            complete,
            SyncScan.ITEMID_WATERMARK
        );
        return new ScopedPage(page, complete ? manifest.hostExternalIds() : Set.of());
    }

    private Manifest capture(String token) {
        var hosts = JsonRpcWatermarkBounds.capture(
            transport, endpoint, token, "host.get", "hostid", hostGroupIds
        );
        if (hosts.ids().isEmpty()) return Manifest.empty();

        List<Long> hostIds = hosts.ids();
        Set<String> hostIdSet = hostIds.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        String hostIdParams = "\"hostids\":" + hostIds;
        String countRequest = request("item.get", "\"countOutput\":true," + hostIdParams);
        long count = transport.readCount(transport.exchange(endpoint, countRequest, token));
        if (count < 0 || count > JsonRpcWatermarkBounds.MAX_IDS) {
            throw new IllegalStateException("Zabbix item manifest limit exceeded");
        }
        String manifestRequest = request("item.get", "\"output\":[\"itemid\",\"hostid\"],"
            + hostIdParams + ",\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":"
            + (JsonRpcWatermarkBounds.MAX_IDS + 1));
        List<Map<String, Object>> rows = transport.readHostArray(transport.exchange(endpoint, manifestRequest, token));
        if (rows.size() != count || rows.size() > JsonRpcWatermarkBounds.MAX_IDS) {
            throw new IllegalStateException("Zabbix item manifest changed");
        }
        List<ItemIdentity> items = new ArrayList<>(rows.size());
        long previous = 0;
        for (Map<String, Object> row : rows) {
            long itemId = JsonRpcWatermarkBounds.id(row, "itemid");
            long hostId = JsonRpcWatermarkBounds.id(row, "hostid");
            if (itemId <= previous || !hostIdSet.contains(Long.toString(hostId))) {
                throw new IllegalStateException("Zabbix item is outside the captured host scope");
            }
            previous = itemId;
            items.add(new ItemIdentity(itemId, hostId));
        }
        return new Manifest(hosts, List.copyOf(items), digest(hosts.digest() + "|" + items));
    }

    private List<Map<String, Object>> readMetadata(String token, Manifest manifest, List<ItemIdentity> selected) {
        List<Long> itemIds = selected.stream().map(ItemIdentity::itemId).toList();
        String params = "\"output\":[\"itemid\",\"key_\",\"name\",\"value_type\",\"units\",\"hostid\"],"
            + "\"itemids\":" + itemIds + ",\"hostids\":" + manifest.hosts().ids()
            + ",\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":" + itemIds.size();
        String body = request("item.get", params);
        return transport.readHostArray(transport.exchange(endpoint, body, token));
    }

    private static boolean validRows(List<Map<String, Object>> rows, Manifest manifest, List<ItemIdentity> selected) {
        if (rows.size() != selected.size()) return false;
        Set<Long> hostIds = Set.copyOf(manifest.hosts().ids());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long itemId;
            long hostId;
            try {
                itemId = JsonRpcWatermarkBounds.id(row, "itemid");
                hostId = JsonRpcWatermarkBounds.id(row, "hostid");
            } catch (IllegalStateException malformed) {
                return false;
            }
            ItemIdentity expected = selected.get(i);
            if (itemId != expected.itemId() || hostId != expected.hostId() || !hostIds.contains(hostId)) return false;
        }
        return true;
    }

    private static ScopedPage incomplete() {
        return new ScopedPage(new Page(List.of(), null, false, SyncScan.ITEMID_WATERMARK), Set.of());
    }

    private static String request(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{" + params + "},\"id\":1}";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record ItemIdentity(long itemId, long hostId) { }

    private record Manifest(JsonRpcWatermarkBounds hosts, List<ItemIdentity> items, String digest) {
        static Manifest empty() {
            return new Manifest(new JsonRpcWatermarkBounds(List.of(), ""), List.of(), "");
        }

        List<String> hostIds() {
            return hosts.ids().stream().map(String::valueOf).toList();
        }

        Set<String> hostExternalIds() {
            return Set.copyOf(new LinkedHashSet<>(hostIds()));
        }

        String cursor(int position) {
            return CURSOR_PREFIX + "|" + digest + "|" + items.size() + "|" + position;
        }
    }

    private record Cursor(String digest, int count, int position) {
        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            String[] parts = cursor.split("\\|", -1);
            if (parts.length != 4 || !CURSOR_PREFIX.equals(parts[0]) || !parts[1].matches("[0-9a-f]{64}")
                || !parts[2].matches("[1-9][0-9]{0,3}") || !parts[3].matches("[1-9][0-9]{0,3}")) {
                throw new IllegalArgumentException("Invalid registered item cursor");
            }
            int count = Integer.parseInt(parts[2]);
            int position = Integer.parseInt(parts[3]);
            if (count > JsonRpcWatermarkBounds.MAX_IDS || position >= count) {
                throw new IllegalArgumentException("Invalid registered item cursor");
            }
            return new Cursor(parts[1], count, position);
        }

        boolean matches(Manifest manifest) {
            return count == manifest.items().size() && digest.equals(manifest.digest());
        }
    }
}
