package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.acme.opsweave.integration.application.IngestMetricHistoryUseCase;
import org.junit.jupiter.api.Test;

class HistoryPollerTest {
    private HistoryWorkerProperties properties(String item) {
        return new HistoryWorkerProperties("http://127.0.0.1:8080", "fixture", "labeled-fixture",
            "tenant-demo", "zabbix-test", item, "history-v1", 0, 60, 120, 10, 4, 10000,
            "http://127.0.0.1:8428", "", "", "");
    }
    @Test void boundedStreamsRejectAmbiguityDuplicatesAndInvalidIds() {
        var p = properties("");
        for (String ids : new String[]{"1:a,1:b", "1:a,", "0:a", "-1:a", "1:a,2:a,3:a,4:a,5:a,6:a,7:a,8:a,9:a", "1", "1:", "1:x:-1", "1:x:01", "1:x:10000000000"})
            assertThrows(IllegalArgumentException.class, () -> HistoryPoller.targets(p, ids));
        assertThrows(IllegalArgumentException.class, () -> HistoryPoller.targets(properties("1"), "2:b"));
        assertEquals("history-v1", HistoryPoller.targets(properties("1"), "").getFirst().stream().streamName());
        assertEquals(8, HistoryPoller.targets(p, "1:a,2:b,3:c,4:d,5:e,6:f,7:g,8:h").size());
        assertEquals("saved-stream-b", HistoryPoller.targets(p, "1:original-stream, 2:saved-stream-b").getLast().stream().streamName());
    }
    @Test void failedStreamDoesNotPreventLaterIndependentStreamsOrRetryWithinTheTick() {
        var p = properties("");
        var streams = HistoryPoller.targets(p, "1:original-stream:100,2:saved-stream-b:200");
        var useCase = mock(IngestMetricHistoryUseCase.class);
        // Both calls deliberately fail: each is attempted once and only the next tick retries.
        when(useCase.poll(any(), anyLong())).thenThrow(new IllegalStateException("fixture upstream failure"));
        var poller = new HistoryPoller(p, useCase, "1:original-stream:100,2:saved-stream-b:200");
        poller.poll();
        var order = inOrder(useCase);
        order.verify(useCase).poll(streams.get(0).stream(), 100);
        order.verify(useCase).poll(streams.get(1).stream(), 200);
        verifyNoMoreInteractions(useCase);
    }
}
