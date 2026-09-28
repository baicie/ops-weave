package com.acme.opsweave.integration.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Bounded membership manifest for APIs that support explicit IDs but do not support offset.
 * The cursor pins the manifest digest, count and local position; each page rechecks membership.
 * This proves a complete identity walk, not a transactional snapshot of mutable object fields.
 */
record JsonRpcWatermarkBounds(List<Long> ids, String digest) {
    static final int MAX_IDS = 1_000;

    static JsonRpcWatermarkBounds capture(ZabbixJsonRpcConnector.Transport transport, URI endpoint,
                                         String token, String method, String idField) {
        String countBody = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method
            + "\",\"params\":{\"countOutput\":true},\"id\":1}";
        long count = transport.readCount(transport.exchange(endpoint, countBody, token));
        if (count < 0 || count > MAX_IDS) throw new IllegalStateException("Zabbix manifest limit exceeded");
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{"
            + "\"output\":[\"" + idField + "\"],\"sortfield\":\"" + idField
            + "\",\"sortorder\":\"ASC\",\"limit\":" + (MAX_IDS + 1) + "},\"id\":1}";
        List<Long> ids = transport.readHostArray(transport.exchange(endpoint, body, token)).stream()
            .map(row -> id(row, idField)).toList();
        if (ids.size() != count || ids.size() > MAX_IDS) throw new IllegalStateException("Zabbix manifest changed");
        long previous = 0;
        for (long id : ids) {
            if (id <= previous) throw new IllegalStateException("Zabbix manifest is not strictly ordered");
            previous = id;
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((method + ":" + ids).getBytes(StandardCharsets.UTF_8)));
            return new JsonRpcWatermarkBounds(ids, digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static long id(Map<String, Object> row, String field) {
        String text = String.valueOf(row.get(field));
        if (!text.matches("[1-9][0-9]{0,18}")) throw new IllegalStateException("Invalid Zabbix object id");
        try { return Long.parseLong(text); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Invalid Zabbix object id"); }
    }

    String cursor(int next) { return "ids-v1|" + digest + "|" + ids.size() + "|" + next; }

    static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        String[] parts = cursor.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals("ids-v1") || !parts[1].matches("[0-9a-f]{64}")
            || !parts[2].matches("[1-9][0-9]{0,3}") || !parts[3].matches("[1-9][0-9]{0,3}"))
            throw new IllegalArgumentException("Invalid scan cursor");
        int count = Integer.parseInt(parts[2]), position = Integer.parseInt(parts[3]);
        if (count > MAX_IDS || position >= count) throw new IllegalArgumentException("Invalid scan cursor");
        return new Cursor(parts[1], count, position);
    }

    record Cursor(String digest, int count, int position) {
        boolean matches(JsonRpcWatermarkBounds manifest) {
            return count == manifest.ids.size() && digest.equals(manifest.digest);
        }
    }
}
