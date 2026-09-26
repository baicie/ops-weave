package com.acme.opsweave.inventory.domain;

import java.util.Map;

/** Conservative JSON size/depth budget shared by read adapters; never silently truncate attributes. */
public final class EntityReadLimits {
    private EntityReadLimits() {}
    public static void check(Map<String, Object> attributes) { size(attributes, 0, new int[] {16_384}); }
    private static void size(Object value, int depth, int[] budget) {
        if (depth > 8 || (budget[0] -= 4) < 0) throw new IllegalStateException("Entity attributes exceed read budget");
        if (value == null || value instanceof Boolean) { budget[0] -= 5; }
        else if (value instanceof Number number) {
            String encoded = number.toString();
            if (encoded.length() > 64 || encoded.equals("NaN") || encoded.contains("Infinity")) throw new IllegalStateException("Invalid entity attribute number");
            budget[0] -= encoded.length();
        }
        else if (value instanceof String text) {
            if (text.length() > budget[0] / 6) throw new IllegalStateException("Entity attributes exceed read budget");
            budget[0] -= text.length() * 6;
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) { size(entry.getKey(), depth + 1, budget); size(entry.getValue(), depth + 1, budget); }
        } else if (value instanceof Iterable<?> list) { for (var item : list) size(item, depth + 1, budget); }
        else throw new IllegalStateException("Unsupported entity attribute type");
        if (budget[0] < 0) throw new IllegalStateException("Entity attributes exceed read budget");
    }
}
