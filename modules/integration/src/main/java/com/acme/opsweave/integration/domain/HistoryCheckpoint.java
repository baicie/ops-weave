package com.acme.opsweave.integration.domain;

/** Metadata only: no samples or per-point identifiers are stored in PostgreSQL. */
public record HistoryCheckpoint(long initialFrom, long completedThrough, String seriesHash, long revision) {
    public static String fingerprint(String series, String source, String sink) {
        try {
            var bytes = (series + "\0" + source + "\0" + sink).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public HistoryCheckpoint {
        if (initialFrom < 0 || initialFrom > 9_999_999_999L || completedThrough < initialFrom - 1 || completedThrough > 9_999_999_999L
            || revision < 0 || !seriesHash.matches("([a-f0-9]{64})?")) throw new IllegalArgumentException("Invalid checkpoint");
    }

    public static HistoryCheckpoint initial(long from) { return new HistoryCheckpoint(from, from - 1, "", 0); }

    public HistoryCheckpoint accepted(long till, String hash) {
        if (till < completedThrough || (!seriesHash.isEmpty() && !seriesHash.equals(hash))) {
            throw new IllegalArgumentException("SERIES_OR_CHECKPOINT_CHANGED");
        }
        return new HistoryCheckpoint(initialFrom, till, hash, Math.addExact(revision, 1));
    }
}
