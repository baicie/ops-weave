package com.acme.opsweave.ingestion.history;

/** Transport adapter port. A 401 discards a token, but never retries the failed request. */
public interface HistoryAuthorization {
    String authorization();
    default void unauthorized() { }
    default String checkpointIdentity() { return "explicit-service-adapter"; }
}
