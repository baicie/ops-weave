package com.acme.opsweave.ingestion.history;

import java.io.IOException;
import java.net.URI;
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

/** Development adapter: explicit loopback origins, no redirects, bounded bodies and no application retries. */
final class LoopbackHttp {
    private final URI origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    LoopbackHttp(URI origin) {
        if (!"http".equals(origin.getScheme()) || !Set.of("127.0.0.1", "[::1]", "::1").contains(origin.getHost())
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
            || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("History worker endpoints must be explicit loopback HTTP origins");
        }
        this.origin = origin;
    }

    HttpResponse<String> request(String method, String path, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(25));
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try { return client.send(builder.build(), ignored -> new BoundedBody()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("HTTP_REQUEST_INTERRUPTED"); }
        catch (IOException failed) { throw new IllegalStateException("HTTP_REQUEST_FAILED"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) bytes += buffer.remaining();
            if (bytes > 2 * 1024 * 1024) {
                failed = true; subscription.cancel(); delegate.onError(new IOException("HTTP_RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { if (!failed) delegate.onError(failure); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
