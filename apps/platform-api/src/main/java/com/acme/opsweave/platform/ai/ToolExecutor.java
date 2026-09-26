package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.ToolFailure;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import java.util.function.Supplier;

/** No queue beyond four admitted tasks. Timed-out work retains its slot until it actually exits. */
public final class ToolExecutor implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final Duration timeout;
    public ToolExecutor() { this(4, Duration.ofSeconds(15)); }
    ToolExecutor(int concurrency, Duration timeout) { permits = new Semaphore(concurrency); this.timeout = timeout; }
    public <T> T run(Supplier<T> work) {
        if (!permits.tryAcquire()) throw new ToolFailure(ToolFailure.Code.BUSY);
        var result = new CompletableFuture<T>(); var cancelled = new AtomicBoolean(); var worker = new AtomicReference<Thread>();
        try { executor.execute(() -> {
            worker.set(Thread.currentThread());
            try { if (!cancelled.get()) result.complete(work.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { permits.release(); }
        }); }
        catch (RuntimeException unavailable) { permits.release(); throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
        try { return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException timedOut) { cancelled.set(true); if (worker.get() != null) worker.get().interrupt(); throw new ToolFailure(ToolFailure.Code.DEADLINE); }
        catch (InterruptedException interrupted) { cancelled.set(true); if (worker.get() != null) worker.get().interrupt(); Thread.currentThread().interrupt(); throw new ToolFailure(ToolFailure.Code.DEADLINE); }
        catch (ExecutionException failed) { if (failed.getCause() instanceof RuntimeException error) throw error; throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
    }
    public void close() { executor.shutdownNow(); }
}
