package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.platform.OpsweaveProperties;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One fixed loopback endpoint, four in-flight calls, no queue/redirect/retry, bounded response and deadline. */
@Component
public final class RuntimeDispatcher implements AutoCloseable {
    private final URI endpoint;
    private final HttpClient client;
    private final Semaphore permits = new Semaphore(4);
    public RuntimeDispatcher(OpsweaveProperties properties, @Value("${opsweave.ai.runtime-url:}") String base) {
        if (base.isEmpty()) { endpoint = null; client = null; return; }
        if (!Set.of("dev", "oidc").contains(properties.auth().mode()) || !properties.auth().bindLoopbackOnly()) throw new IllegalStateException("Runtime dispatch requires a loopback internal authentication boundary");
        endpoint = endpoint(base);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
            }).build();
    }
    static URI endpoint(String base) {
        try {
            URI uri = URI.create(base);
            if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
            return uri.resolve("/api/v1/current-diagnoses");
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("Runtime URL must be an explicit loopback HTTP origin"); }
    }
    public void dispatch(String authorization, String body) {
        if (endpoint == null) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
        if (!permits.tryAcquire()) throw new ToolFailure(ToolFailure.Code.BUSY);
        CompletableFuture<HttpResponse<Void>> future = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(62)).header("Authorization", authorization)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            future = client.sendAsync(request, info -> new BoundedBody());
            var response = future.get(62, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new ToolFailure(switch (response.statusCode()) {
                case 400, 422 -> ToolFailure.Code.INVALID_REQUEST; case 401, 403 -> ToolFailure.Code.FORBIDDEN;
                case 404 -> ToolFailure.Code.NOT_FOUND; case 409 -> ToolFailure.Code.INPUT_CHANGED;
                case 410 -> ToolFailure.Code.EXPIRED; case 429 -> ToolFailure.Code.BUSY; case 504 -> ToolFailure.Code.DEADLINE;
                default -> ToolFailure.Code.UNAVAILABLE;
            });
        } catch (TimeoutException timeout) { throw new ToolFailure(ToolFailure.Code.DEADLINE); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ToolFailure(ToolFailure.Code.DEADLINE); }
        catch (ExecutionException unavailable) { throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
        finally { if (future != null && !future.isDone()) future.cancel(true); permits.release(); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int size;
        public CompletionStage<Void> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) { size += buffer.remaining(); if (size > 131072) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("Runtime response exceeds limit")); return; } }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(null); }
    }
    public void close() { if (client != null) client.shutdownNow(); }
}
