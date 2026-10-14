package com.acme.opsweave.platform.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bounded push pump of the run event channel (ADR-104 §2.5). Frames carry readback pages only,
 * a reconnect resumes from the last delivered sequence number without duplicates or loss, replay is
 * side-effect free and every connection is bounded in frames, bytes and time — a slow or broken
 * client never builds a queue and never changes what the store records.
 */
class AiRunEventStreamTest {
    static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000083");
    static final Instant T0 = Instant.parse("2026-10-13T01:00:00Z");

    static AiRunEvent.Event event(long seq, AiRunEvent.Type type) {
        var payload = switch (type) {
            case SUBMITTED -> AiRunEvent.Payload.claim(AiRun.Status.PENDING, 0);
            case CLAIMED -> AiRunEvent.Payload.claim(AiRun.Status.RUNNING, (int) Math.max(1, seq));
            case CANCEL_REQUESTED -> AiRunEvent.Payload.empty();
            case FINISHED -> AiRunEvent.Payload.landing(AiRun.Status.SUCCEEDED, null, null);
            default -> AiRunEvent.Payload.state(AiRun.Status.RUNNING);
        };
        return new AiRunEvent.Event(RUN, seq, type, T0.plusSeconds(seq), AiRunEvent.PAYLOAD_VERSION, payload);
    }

    /** A scripted source over one persisted log; polls are counted and reading writes nothing. */
    static final class Scripted implements AiRunEventStream.Source {
        final List<AiRunEvent.Event> log;
        final AiRunEvent.Bounds bounds;
        final AiRunEvent.Snapshot snapshot;
        int polls;

        Scripted(List<AiRunEvent.Event> log, AiRunEvent.Bounds bounds, AiRunEvent.Snapshot snapshot) {
            this.log = log;
            this.bounds = bounds;
            this.snapshot = snapshot;
        }

        @Override
        public AiRunEvent.Page read(long afterSeq) {
            polls++;
            var rows = new ArrayList<AiRunEvent.Event>();
            for (var record : log) if (record.seq() > afterSeq) rows.add(record);
            // One record per frame, like a record that lands while a reader is connected.
            return AiRunEvent.Page.of(RUN, rows.subList(0, Math.min(2, rows.size())), afterSeq, 1, bounds, snapshot);
        }
    }

    static final class Recording implements AiRunEventStream.Sink {
        final List<String> frames = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        int retries;
        int keepalives;
        final int stopAfter;

        Recording(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public int retry(int millis) {
            retries++;
            return 8;
        }

        @Override
        public int frame(Long lastSeq, String json) throws IOException {
            if (stopAfter > 0 && frames.size() >= stopAfter) throw new IOException("client disconnected");
            frames.add(json);
            ids.add(lastSeq);
            return json.length();
        }

        @Override
        public int keepalive() {
            keepalives++;
            return 12;
        }
    }

    static AiRunEvent.Snapshot snapshot(String status) {
        return new AiRunEvent.Snapshot(status, 2, 1, T0.plusSeconds(9));
    }

    static final AiRunEventStream.Limits LIMITS = new AiRunEventStream.Limits(5_000, 262_144, 10, 0, 20, 60_000);

    @Test
    void framesCarryReadbackPagesUntilTheTerminalSnapshot() throws IOException {
        var log = List.of(event(1, AiRunEvent.Type.SUBMITTED), event(2, AiRunEvent.Type.CLAIMED),
            event(3, AiRunEvent.Type.FINISHED));
        var source = new Scripted(log, new AiRunEvent.Bounds(1, 3), snapshot("SUCCEEDED"));
        var sink = new Recording(0);
        AiRunEventStream.pump(source, sink, 0, LIMITS);
        assertEquals(1, sink.retries);
        assertEquals(List.of(1L, 2L, 3L), sink.ids);
        assertEquals(3, sink.frames.size());
        assertTrue(sink.frames.get(0).contains("\"seq\":1"));
        assertTrue(sink.frames.get(2).contains("\"status\":\"SUCCEEDED\""));
        // The stream closes itself at the terminal snapshot instead of holding the connection.
        assertEquals(4, source.polls);
        assertEquals(0, sink.keepalives);
    }

    @Test
    void reconnectingWithTheLastSequenceNumberResumesWithoutDuplicatesOrLoss() throws IOException {
        var log = new ArrayList<AiRunEvent.Event>();
        for (long seq = 1; seq <= 6; seq++) log.add(event(seq, AiRunEvent.Type.CANCEL_REQUESTED));
        var bounds = new AiRunEvent.Bounds(1, 6);
        // The first connection dies after two frames (a disconnect is not a command).
        var first = new Recording(2);
        assertThrows(IOException.class, () -> AiRunEventStream.pump(
            new Scripted(log, bounds, snapshot("SUCCEEDED")), first, 0, LIMITS));
        assertEquals(List.of(1L, 2L), first.ids);
        // The client resumes from its own cursor: the missed records arrive exactly once.
        var second = new Recording(0);
        AiRunEventStream.pump(new Scripted(log, bounds, snapshot("SUCCEEDED")), second, first.ids.getLast(), LIMITS);
        var delivered = new ArrayList<Long>(first.ids);
        delivered.addAll(second.ids);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), delivered);
        assertEquals(6, delivered.stream().distinct().count());
    }

    @Test
    void theConnectionBudgetIsBoundedAndAQueueNeverBuildsUp() throws IOException {
        var log = new ArrayList<AiRunEvent.Event>();
        for (long seq = 1; seq <= 6; seq++) log.add(event(seq, AiRunEvent.Type.CANCEL_REQUESTED));
        var bounds = new AiRunEvent.Bounds(1, 6);
        // The frame budget ends the stream even though more records exist.
        var frames = new Recording(0);
        AiRunEventStream.pump(new Scripted(log, bounds, snapshot("RUNNING")), frames, 0,
            new AiRunEventStream.Limits(5_000, 262_144, 2, 0, 20, 60_000));
        assertEquals(2, frames.frames.size());
        // The byte budget ends the stream as well.
        var bytes = new Recording(0);
        AiRunEventStream.pump(new Scripted(log, bounds, snapshot("RUNNING")), bytes, 0,
            new AiRunEventStream.Limits(5_000, 400, 50, 0, 20, 60_000));
        assertTrue(bytes.frames.size() < 6, "byte budget must stop the stream early");
        // A slow consumer is never queued for: at most one pull per written frame, plus the terminal check.
        var slow = new Scripted(log, bounds, snapshot("RUNNING"));
        var sink = new Recording(0);
        AiRunEventStream.pump(slow, sink, 0, new AiRunEventStream.Limits(5_000, 262_144, 3, 0, 20, 60_000));
        assertTrue(slow.polls <= sink.frames.size() + 1, "the store is the buffer: no unbounded pull-ahead");
        // The time budget ends an idle stream; nothing is executed while it waits.
        var idle = new Scripted(List.of(), AiRunEvent.Bounds.none(), snapshot("RUNNING"));
        var idleSink = new Recording(0);
        long started = System.nanoTime();
        AiRunEventStream.pump(idle, idleSink, 0, new AiRunEventStream.Limits(120, 262_144, 50, 1, 20, 1));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000);
        assertTrue(idleSink.frames.isEmpty());
        assertTrue(idleSink.keepalives >= 1);
    }

    @Test
    void anExpiredCursorIsPushedAsAnExplicitGapFrame() throws IOException {
        var log = List.of(event(4, AiRunEvent.Type.CANCEL_REQUESTED), event(5, AiRunEvent.Type.CANCEL_REQUESTED));
        var source = new Scripted(log, new AiRunEvent.Bounds(4, 5), snapshot("SUCCEEDED"));
        var sink = new Recording(0);
        AiRunEventStream.pump(source, sink, 0, LIMITS);
        assertEquals(List.of(4L, 5L), sink.ids);
        assertTrue(sink.frames.get(0).contains("\"gap\":true"));
        assertTrue(sink.frames.get(0).contains("\"snapshot\""));
    }

    @Test
    void replayingTheSameCursorDeliversTheSameRecordsAndChangesNothing() throws IOException {
        var log = List.of(event(1, AiRunEvent.Type.SUBMITTED), event(2, AiRunEvent.Type.CLAIMED));
        var bounds = new AiRunEvent.Bounds(1, 2);
        var first = new Recording(0);
        AiRunEventStream.pump(new Scripted(log, bounds, snapshot("SUCCEEDED")), first, 0, LIMITS);
        var second = new Recording(0);
        AiRunEventStream.pump(new Scripted(log, bounds, snapshot("SUCCEEDED")), second, 0, LIMITS);
        assertEquals(first.frames, second.frames);
        assertEquals(first.ids, second.ids);
    }

    @Test
    void invalidStreamBoundsAndCursorsFailClosed() {
        var log = List.of(event(1, AiRunEvent.Type.SUBMITTED));
        var source = new Scripted(log, new AiRunEvent.Bounds(1, 1), snapshot("RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> AiRunEventStream.pump(source, new Recording(0), -1, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new AiRunEventStream.Limits(0, 262_144, 10, 0, 20, 60_000));
        assertThrows(IllegalArgumentException.class, () -> new AiRunEventStream.Limits(5_000, 262_144, 10, 0, 51, 60_000));
        var disclosure = new AiRunControl.Reconciliation(false, true, 0, 0, false);
        assertEquals("RECONCILED", AiRunEvent.reconciled(RUN, 1, disclosure, T0).type().name());
    }
}
