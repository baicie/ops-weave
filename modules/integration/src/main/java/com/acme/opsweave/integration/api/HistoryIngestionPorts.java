package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.HistoryCheckpoint;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryStream;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.util.Map;
import java.util.function.Function;

public final class HistoryIngestionPorts {
    private HistoryIngestionPorts() {}

    public interface SourceReader {
        Slice read(HistoryStream stream, HistoryWindow window);
        default String checkpointIdentity() { throw new Failure(Failure.Code.CONFIGURATION_INVALID); }
    }
    public interface MetricSink {
        void write(MetricWriteBatch batch);
        default String checkpointIdentity() { throw new Failure(Failure.Code.CONFIGURATION_INVALID); }
    }
    public interface CheckpointStore {
        /** Exclusively owns one stream until work and metadata commit finish; any exception rolls back. */
        Result withLock(HistoryStream stream, long initialFrom, Function<HistoryCheckpoint, Update> work);
    }
    public record Slice(Map<String, String> labels, long definitionVersion, long bindingVersion, HistoryPage page) {
        public Slice { labels = Map.copyOf(labels); }
    }
    public record Result(boolean accepted, long from, long till, int readPoints, int confirmedPoints, int collapsedPoints) {
        public static Result idle(long through) { return new Result(false, through, through, 0, 0, 0); }
    }
    public record Update(HistoryCheckpoint checkpoint, Result result) {}

    public static final class Failure extends RuntimeException {
        public enum Code {
            SOURCE_FAILED, INVALID_PAGE, SERIES_CHANGED, PAGE_BUDGET_EXCEEDED, INVALID_POINTS,
            MILLISECOND_COLLISION, VALUE_PRECISION_LOSS, UNSUPPORTED_METRIC_LABEL,
            SINK_FAILED, SINK_UNCONFIRMED, STORED_VALUE_CONFLICT, CHECKPOINT_FAILED, CHECKPOINT_BUSY, CONFIGURATION_INVALID
        }
        private final Code code;
        public Failure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
}
