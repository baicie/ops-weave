package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import java.io.IOException;

/**
 * The bounded push pump of the run event channel (M5 5c, ADR-104 §2.5). The channel is only a push
 * transport over the same cursor protocol as the readback API: it delivers persisted records, it
 * never carries a command and it never triggers anything by delivering one. Reconnecting with the
 * last delivered sequence number re-reads the same records with zero side effects.
 *
 * <p>Bounds are explicit and per connection: at most {@link Limits#maxMillis()} of streaming, at
 * most {@link Limits#maxBytes()} of frames, at most {@link Limits#maxFrames()} frames and one frame
 * in flight at a time — a slow client never builds a queue (the store is the buffer) and is simply
 * disconnected at the byte or time limit, then resumes from its own cursor. When the run is terminal
 * and every record has been delivered the pump closes the stream instead of holding the connection.
 */
final class AiRunEventStream {
    private AiRunEventStream() {}

    /** The per-connection stream budget (the shared HTTP boundary keeps responses bounded). */
    record Limits(long maxMillis, int maxBytes, int maxFrames, long pollMillis, int frameEvents, long keepaliveMillis) {
        public Limits {
            if (maxMillis < 1 || maxBytes < 256 || maxFrames < 1 || pollMillis < 0 || frameEvents < 1
                || frameEvents > 50 || keepaliveMillis < 1) {
                throw new IllegalArgumentException("stream limits out of bounds");
            }
        }
    }

    static final Limits LIMITS = new Limits(60_000, 262_144, 300, 1_000, 20, 15_000);

    /** The reconnect hint every stream starts with; the client still resumes from its own cursor. */
    static final int RETRY_MILLIS = 2_000;

    /** One cursor readback pull. Reads are side-effect free; the same cursor answers the same. */
    interface Source {
        AiRunEvent.Page read(long afterSeq);
    }

    /** The transport sink. Each call writes and flushes exactly one bounded frame. */
    interface Sink {
        int retry(int millis) throws IOException;

        /** One readback page as one frame; {@code lastSeq} is the SSE id of the last record in it. */
        int frame(Long lastSeq, String json) throws IOException;

        int keepalive() throws IOException;
    }

    /**
     * Push persisted records from the cursor until the budget runs out or the run has ended and
     * every record was delivered. An empty page is never sent as a frame: an idle stream keeps only
     * the keepalive comments, and a terminal snapshot with no new records closes the stream.
     */
    static void pump(Source source, Sink sink, long afterSeq, Limits limits) throws IOException {
        if (afterSeq < 0) throw new IllegalArgumentException("stream cursor out of bounds");
        long cursor = afterSeq;
        int bytes = sink.retry(RETRY_MILLIS);
        int frames = 0;
        long started = System.nanoTime();
        long deadline = started + limits.maxMillis() * 1_000_000L;
        long lastWrite = started;
        while (frames < limits.maxFrames() && bytes < limits.maxBytes() && System.nanoTime() < deadline) {
            var page = source.read(cursor);
            if (!page.items().isEmpty() || page.gap()) {
                Long lastSeq = page.items().isEmpty() ? null : page.items().get(page.items().size() - 1).seq();
                bytes += sink.frame(lastSeq, AiRunJson.storage(page));
                frames++;
                if (lastSeq != null) cursor = lastSeq;
                lastWrite = System.nanoTime();
                continue;
            }
            if (page.snapshot().terminal()) return;
            long now = System.nanoTime();
            if (now - lastWrite >= limits.keepaliveMillis() * 1_000_000L) {
                bytes += sink.keepalive();
                lastWrite = now;
            }
            if (limits.pollMillis() <= 0) continue;
            long nap = Math.min(limits.pollMillis() * 1_000_000L, deadline - System.nanoTime());
            if (nap <= 0) return;
            try {
                Thread.sleep(nap / 1_000_000L, (int) (nap % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
