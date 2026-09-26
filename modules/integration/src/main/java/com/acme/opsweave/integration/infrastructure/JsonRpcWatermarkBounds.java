package com.acme.opsweave.integration.infrastructure;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The snapshot bound of one JSON-RPC watermark walk: the highest object id and the row count captured
 * before the first page. The bound travels inside the opaque cursor, so a resumed page cannot silently
 * drop it, and a cursor that does not carry a bound is refused instead of guessed. A connector must
 * never claim a snapshot it did not verify against this bound.
 */
record JsonRpcWatermarkBounds(long watermark, long expected, int offset, long previous, long observed) {
    /** Captures the bound with the source itself; any transport failure propagates and is not a snapshot. */
    static JsonRpcWatermarkBounds capture(
        ZabbixJsonRpcConnector.Transport transport,
        URI endpoint,
        String token,
        String method,
        String idField
    ) {
        String watermarkBody = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{"
            + "\"output\":[\"" + idField + "\"],\"sortfield\":\"" + idField + "\",\"sortorder\":\"DESC\",\"limit\":1},\"id\":1}";
        List<Map<String, Object>> highest = transport.readHostArray(transport.exchange(endpoint, watermarkBody, token));
        long watermark = 0;
        for (Map<String, Object> row : highest) {
            Object id = row.get(idField);
            if (id == null) {
                continue;
            }
            try {
                watermark = Math.max(watermark, Long.parseLong(String.valueOf(id).trim()));
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("Zabbix " + idField + " is not numeric");
            }
        }
        String countBody = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{\"countOutput\":true},\"id\":1}";
        long expected = transport.readCount(transport.exchange(endpoint, countBody, token));
        if (expected < 0 || expected > 5_000_000L) {
            throw new IllegalStateException("Zabbix row count is out of range");
        }
        return new JsonRpcWatermarkBounds(watermark, expected, 0, 0, 0);
    }

    static JsonRpcWatermarkBounds decode(String cursor) {
        String[] parts = cursor.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid scan cursor");
        }
        try {
            return new JsonRpcWatermarkBounds(
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2]),
                Integer.parseInt(parts[0]),
                Long.parseLong(parts[3]),
                Long.parseLong(parts[4])
            );
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid scan cursor");
        }
    }

    String encode(int nextOffset, long lastId, long observedRows) {
        return nextOffset + "|" + watermark + "|" + expected + "|" + lastId + "|" + observedRows;
    }

    /**
     * True when the page closed the walk at the captured bound: every row inside the watermark was
     * observed, the highest id was seen, and no row arrived out of order or twice.
     */
    boolean verified(boolean atEnd, boolean ordered, long observedRows, long lastId) {
        return atEnd && ordered && observedRows == expected && lastId == watermark;
    }
}
