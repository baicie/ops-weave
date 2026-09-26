package com.acme.opsweave.platform.identity;

import com.acme.opsweave.aicontrol.domain.ToolFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Four in-flight, 65s capabilities. No IdP token leaves Java; each use rechecks the live browser session/grant. */
public final class RuntimeDelegations {
    public static final String ATTR = "opsweave.runtime.delegation";
    public static final class Denied extends RuntimeException { private Denied() { super("Delegation denied"); } }
    private static final class Grant {
        final HttpSession session; final String sessionId; final JsonNode request; final Instant deadline;
        int calls; boolean opening, modelReserved, modelReported; UUID readSession;
        Grant(HttpSession session, String sessionId, JsonNode request, Instant deadline) { this.session = session; this.sessionId = sessionId; this.request = request; this.deadline = deadline; }
    }
    public final class Lease implements AutoCloseable {
        private final String token, key;
        private Lease(String token, String key) { this.token = token; this.key = key; }
        public String authorization() { return "Bearer " + token; }
        public void checkActive() { synchronized (entries) { var grant = entries.get(key); try {
            if (grant == null || !clock.instant().isBefore(grant.deadline) || !sessions.identity(grant.session).id().equals(grant.sessionId)) throw new Denied();
            sessions.resolve(grant.session);
        } catch (RuntimeException denied) { throw new Denied(); } } }
        public void close() { synchronized (entries) { entries.remove(key); } }
    }
    private final OidcSessions sessions;
    private final Clock clock;
    private final Map<String,Grant> entries = new HashMap<>();
    public RuntimeDelegations(OidcSessions sessions) { this(sessions, Clock.systemUTC()); }
    RuntimeDelegations(OidcSessions sessions, Clock clock) { this.sessions = sessions; this.clock = clock; }
    public Lease issue(HttpServletRequest request, JsonNode body) {
        var session = request.getSession(false); sessions.resolve(session); var identity = sessions.identity(session);
        synchronized (entries) {
            entries.values().removeIf(g -> !clock.instant().isBefore(g.deadline));
            if (entries.size() >= 4) throw new ToolFailure(ToolFailure.Code.BUSY);
            var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); String token = "owd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), key = digest(token);
            entries.put(key, new Grant(session, identity.id(), body.deepCopy(), clock.instant().plusSeconds(65))); return new Lease(token, key);
        }
    }
    public HttpServletRequest authorize(HttpServletRequest request) {
        try {
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1 || !headers.getFirst().matches("Bearer owd_[A-Za-z0-9_-]{43}") || request.getHeader("Cookie") != null
                || request.getHeader("Origin") != null || !Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr())
                || !request.getParameterMap().isEmpty()) throw new Denied();
            final Grant grant; synchronized (entries) { grant = entries.get(digest(headers.getFirst().substring(7))); }
            if (grant == null || !clock.instant().isBefore(grant.deadline) || !sessions.identity(grant.session).id().equals(grant.sessionId)) throw new Denied();
            var principal = sessions.resolve(grant.session);
            byte[] bytes = request.getInputStream().readNBytes(65537); if (bytes.length > 65536) throw new Denied();
            String path = request.getRequestURI();
            synchronized (grant) {
                boolean modelCall = path.equals("/api/v1/ai/model-calls/reservations") || path.equals("/api/v1/ai/model-calls/reports");
                if (!modelCall && ++grant.calls > 8) throw new Denied();
                if (request.getMethod().equals("GET") && path.equals("/api/v1/ai/insights/" + FileIdentityGrants.text(grant.request, "runId"))) {
                    if (bytes.length != 0) throw new Denied();
                } else {
                    if (!request.getMethod().equals("POST")) throw new Denied();
                    var body = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
                    switch (path) {
                        case "/api/v1/ai/model-calls/reservations", "/api/v1/ai/model-calls/reports" -> {
                            if(grant.readSession == null || !grant.readSession.toString().equals(FileIdentityGrants.text(body,"sessionId")) || !same(body,grant.request,"runId")) throw new Denied();
                            if(path.endsWith("/reservations")) { if(grant.modelReserved) throw new Denied(); grant.modelReserved = true; }
                            else { if(!grant.modelReserved || grant.modelReported) throw new Denied(); grant.modelReported = true; }
                        }
                        case "/api/v1/ai/read-sessions" -> {
                            if (grant.opening || !same(body, grant.request, "incidentId", "timeRange", "knowledgeMode")) throw new Denied(); grant.opening = true;
                        }
                        case "/api/v1/tools/incident.get/2.0.0" -> { sessionHeader(request, grant); if (!same(body, grant.request, "incidentId")) throw new Denied(); }
                        case "/api/v1/tools/metric.summary/2.0.0" -> { sessionHeader(request, grant); if (!same(body, grant.request, "incidentId", "timeRange")) throw new Denied(); }
                        case "/api/v1/tools/evidence.get/2.0.0" -> sessionHeader(request, grant);
                        case "/api/v1/ai/insights" -> {
                            if (grant.readSession == null || !grant.readSession.toString().equals(FileIdentityGrants.text(body, "sessionId"))
                                || !same(body, grant.request, "runId", "question")) throw new Denied();
                        }
                        default -> throw new Denied();
                    }
                }
            }
            request.setAttribute(ATTR, grant); ClientIdentityOverride.attach(request, principal);
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthenticationToken(principal));
            return new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() { var input = new ByteArrayInputStream(bytes); return new ServletInputStream() {
                    public int read() { return input.read(); } public boolean isFinished() { return input.available() == 0; }
                    public boolean isReady() { return true; } public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                }; }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
            };
        } catch (Exception denied) { throw new Denied(); }
    }
    public static void bindReadSession(HttpServletRequest request, UUID id) {
        if (!(request.getAttribute(ATTR) instanceof Grant grant)) return;
        synchronized (grant) { if (grant.readSession != null) throw new Denied(); grant.readSession = id; }
    }
    private static void sessionHeader(HttpServletRequest request, Grant grant) {
        var values = Collections.list(request.getHeaders("X-OpsWeave-Read-Session"));
        if (grant.readSession == null || values.size() != 1 || !grant.readSession.toString().equals(values.getFirst())) throw new Denied();
    }
    private static boolean same(JsonNode a, JsonNode b, String... fields) { return Arrays.stream(fields).allMatch(f -> a.has(f) && (f.equals("timeRange")
        ? Instant.parse(FileIdentityGrants.text(a.get(f), "from")).equals(Instant.parse(FileIdentityGrants.text(b.get(f), "from")))
            && Instant.parse(FileIdentityGrants.text(a.get(f), "to")).equals(Instant.parse(FileIdentityGrants.text(b.get(f), "to")))
        : a.get(f).equals(b.get(f)))); }
    private static String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(); } }
}
