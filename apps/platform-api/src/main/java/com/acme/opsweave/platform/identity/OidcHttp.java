package com.acme.opsweave.platform.identity;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.http.client.*;

/** Fixed token/JWK endpoints, shared four-call budget, no redirect/retry, 5s and 64KiB bounds. */
final class OidcHttp {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "oidc-response-deadline"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    static ClientHttpRequestFactory factory() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
        var factory = new JdkClientHttpRequestFactory(client); factory.setReadTimeout(Duration.ofSeconds(5)); return factory;
    }
    static ClientHttpRequestInterceptor boundary(OidcSettings settings) {
        return boundary(Set.of(URI.create(settings.tokenUri()), URI.create(settings.jwkSetUri())));
    }
    static ClientHttpRequestInterceptor boundary(Set<URI> allowed) {
        var permits = new Semaphore(4); var endpoints = Set.copyOf(allowed);
        return (request, body, execution) -> {
            if (!endpoints.contains(request.getURI()) || !permits.tryAcquire()) throw new IOException("Identity provider unavailable");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ClientHttpResponse upstream = null;
            try {
                upstream = execution.execute(request, body);
                var raw = upstream.getBody();
                if (upstream.getHeaders().getContentLength() > 65536) { raw.close(); throw new IOException("Identity response exceeds limit"); }
                final var response = upstream;
                return new ClientHttpResponse() {
                    private final AtomicBoolean closed = new AtomicBoolean();
                    private volatile boolean expired;
                    private final ScheduledFuture<?> timer = DEADLINES.schedule(() -> { expired = true; close(); }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    private final InputStream bounded = new FilterInputStream(raw) {
                        int count;
                        private int add(int n) throws IOException { check(); if (n > 0 && (count += n) > 65536) { close(); throw new IOException("Identity response exceeds limit"); } return n; }
                        private void check() throws IOException { if (expired || System.nanoTime() >= deadline) throw new IOException("Identity response timed out"); }
                        public int read() throws IOException { check(); int n = in.read(); add(n < 0 ? 0 : 1); return n; }
                        public int read(byte[] b, int off, int len) throws IOException { check(); return add(in.read(b, off, len)); }
                    };
                    public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                    public String getStatusText() throws IOException { return response.getStatusText(); }
                    public HttpHeaders getHeaders() { return response.getHeaders(); }
                    public InputStream getBody() { return bounded; }
                    public void close() { if (closed.compareAndSet(false, true)) {
                        if (timer != null) timer.cancel(false);
                        try { raw.close(); } catch (IOException ignored) { }
                        try { response.close(); } finally { permits.release(); }
                    } }
                };
            } catch (IOException | RuntimeException error) {
                if (upstream != null) { try { upstream.getBody().close(); } catch (IOException ignored) { } upstream.close(); }
                permits.release(); throw error;
            }
        };
    }
}
