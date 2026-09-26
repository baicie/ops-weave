package com.acme.opsweave.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {
    @Test void timeoutDoesNotFreeCapacityWhileUnderlyingWorkIsStillRunning() throws Exception {
        var gate = new CountDownLatch(1); var exited = new CountDownLatch(1);
        try (var executor = new ToolExecutor(1, Duration.ofMillis(100))) {
            try {
                var error = assertThrows(ToolFailure.class, () -> executor.run(() -> {
                    try { while (gate.getCount() > 0) { try { gate.await(); } catch (InterruptedException ignored) { /* simulate an uncooperative port */ } } return "done"; }
                    finally { exited.countDown(); }
                }));
                assertEquals(ToolFailure.Code.DEADLINE, error.code());
                assertEquals(ToolFailure.Code.BUSY, assertThrows(ToolFailure.class, () -> executor.run(() -> "not admitted")).code());
            } finally { gate.countDown(); }
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            String result = null;
            for (int i = 0; i < 20 && result == null; i++) {
                try { result = executor.run(() -> "recovered"); }
                catch (ToolFailure failure) { assertEquals(ToolFailure.Code.BUSY, failure.code()); Thread.sleep(10); }
            }
            assertEquals("recovered", result);
        }
    }
    @Test void ordinaryFailuresReleaseCapacityAndPreserveSanitizedFailureType() {
        try (var executor = new ToolExecutor(1, Duration.ofSeconds(1))) {
            assertEquals(ToolFailure.Code.FORBIDDEN, assertThrows(ToolFailure.class, () -> executor.run(() -> { throw new ToolFailure(ToolFailure.Code.FORBIDDEN); })).code());
            assertEquals("ok", executor.run(() -> "ok"));
        }
    }
}
