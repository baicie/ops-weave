package com.acme.opsweave.integration.domain;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Bounded cursor pages over one deterministic retained ordering. The cursor names the last
 * returned element; continuation is strictly after it, so later inserts never leak into
 * following pages and pages neither overlap nor reorder. A missing cursor is an explicit
 * NOT_FOUND instead of a guessed position.
 */
public final class WorkflowPages {

    /** One page: items, the cursor of the last item (null without a next page) and hasMore. */
    public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {
        public CursorPage {
            items = List.copyOf(items);
            if ((nextCursor == null) == hasMore) throw new IllegalArgumentException("cursor disclosure mismatch");
            if (hasMore && items.isEmpty()) throw new IllegalArgumentException("an empty page cannot have more");
        }
    }

    private static final Pattern ENTRY_CURSOR = Pattern.compile("^([a-z][a-z0-9_-]{0,47}):([1-9][0-9]{0,3}|10000)$");
    private static final Pattern RUN_CURSOR = Pattern.compile("^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$");

    private WorkflowPages() {}

    /** Page size is requested by the client but bounded by the server contract. */
    public static int limit(Integer requested) {
        int limit = requested == null ? 20 : requested;
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("page limit out of bounds");
        return limit;
    }

    /** Entry cursor: the closed {@code <workflowId>:<revision>} reference of one entry. */
    public static String entryCursor(String id, int revision) {
        WorkflowDefinition.ref(id, revision);
        return id + ":" + revision;
    }

    public static String entryCursor(String after) {
        if (after == null) throw new IllegalArgumentException("missing entry cursor");
        var match = ENTRY_CURSOR.matcher(after);
        if (!match.matches()) throw new IllegalArgumentException("invalid entry cursor");
        WorkflowDefinition.ref(match.group(1), Integer.parseInt(match.group(2)));
        return after;
    }

    /** Run cursor: the immutable receipt UUID of one recorded run. */
    public static String runCursor(UUID id) {
        if (id == null) throw new IllegalArgumentException("missing run cursor");
        return id.toString();
    }

    public static String runCursor(String after) {
        if (after == null || !RUN_CURSOR.matcher(after).matches()) throw new IllegalArgumentException("invalid run cursor");
        return after;
    }

    /**
     * Slice the next page from a complete deterministic ordering. {@code after} must name an
     * element of {@code rows} (validated by {@code key}); unknown cursors fail closed.
     */
    public static <T> CursorPage<T> page(List<T> rows, int limit, Function<T, String> key, String after) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("page limit out of bounds");
        int start = 0;
        if (after != null) {
            int found = -1;
            for (int i = 0; i < rows.size(); i++) if (after.equals(key.apply(rows.get(i)))) { found = i; break; }
            if (found < 0) throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);
            start = found + 1;
        }
        int end = Math.min(rows.size(), start + limit);
        var items = List.copyOf(rows.subList(start, end));
        boolean hasMore = end < rows.size();
        return new CursorPage<>(items, hasMore ? key.apply(items.get(items.size() - 1)) : null, hasMore);
    }
}
