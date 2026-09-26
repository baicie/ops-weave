package com.acme.opsweave.ingestion.history;

import java.io.IOException;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed origin adapter: existing loopback development profile, or explicit HTTPS service profile. */
final class LoopbackHttp {
    private final URI origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
        }).build();

    LoopbackHttp(URI origin) {
        this(origin, true);
    }
    LoopbackHttp(URI origin, boolean loopbackTest) {
        boolean transport = loopbackTest ? "http".equals(origin.getScheme()) && Set.of("127.0.0.1", "[::1]", "::1").contains(origin.getHost()) : "https".equals(origin.getScheme());
        if (!transport || origin.getHost() == null
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
            || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("History worker endpoints require explicit HTTPS or loopback test origins");
        }
        this.origin = origin;
    }

    HttpResponse<String> request(String method, String path, String body, Map<String, String> headers) {
        return request(method, path, body, headers, 2 * 1024 * 1024, Duration.ofSeconds(25));
    }
    HttpResponse<String> request(String method, String path, String body, Map<String, String> headers, int bytes, Duration timeout) {
        var target = origin.resolve(path);
        if (!path.startsWith("/") || path.startsWith("//") || !target.getScheme().equals(origin.getScheme()) || !target.getRawAuthority().equals(origin.getRawAuthority()) || target.getFragment() != null)
            throw new IllegalArgumentException("HTTP_TARGET_DENIED");
        var builder = HttpRequest.newBuilder(target).timeout(timeout);
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        // HttpRequest.timeout alone does not bound a response body that stalls after headers.
        var response = client.sendAsync(builder.build(), ignored -> new BoundedBody(bytes));
        try { return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            response.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("HTTP_REQUEST_INTERRUPTED");
        } catch (TimeoutException expired) {
            response.cancel(true); throw new IllegalStateException("HTTP_REQUEST_TIMEOUT");
        } catch (ExecutionException failed) { throw new IllegalStateException("HTTP_REQUEST_FAILED"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;
        private final int limit;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) bytes += buffer.remaining();
            if (bytes > limit) {
                failed = true; subscription.cancel(); delegate.onError(new IOException("HTTP_RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { if (!failed) delegate.onError(failure); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
