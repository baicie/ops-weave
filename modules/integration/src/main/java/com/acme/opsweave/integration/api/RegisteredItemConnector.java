package com.acme.opsweave.integration.api;

import java.util.Objects;
import java.util.Set;

/** Item connector whose completed scan also proves the host scope used for that scan. */
public interface RegisteredItemConnector extends Connector {
    ScopedPage fetchScoped(SourceContext source, String cursor, int limit);

    @Override
    default Page fetch(SourceContext source, String cursor, int limit) {
        return fetchScoped(source, cursor, limit).page();
    }

    /** Host IDs are present only on a completed, fully revalidated page. */
    record ScopedPage(Page page, Set<String> verifiedHostExternalIds) {
        public ScopedPage {
            Objects.requireNonNull(page, "page");
            verifiedHostExternalIds = Set.copyOf(Objects.requireNonNull(verifiedHostExternalIds, "verifiedHostExternalIds"));
            if (!page.snapshotComplete() && !verifiedHostExternalIds.isEmpty()) {
                throw new IllegalArgumentException("Incomplete item scan cannot expose a verified host scope");
            }
            if (page.snapshotComplete() && verifiedHostExternalIds.isEmpty()) {
                throw new IllegalArgumentException("Complete registered item scan requires a verified host scope");
            }
        }
    }
}
