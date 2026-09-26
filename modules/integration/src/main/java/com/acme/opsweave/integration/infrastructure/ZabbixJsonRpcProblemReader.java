package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.ZabbixProblemPort;
import com.acme.opsweave.integration.domain.ProblemReadException;
import com.acme.opsweave.integration.domain.ProblemReadWindow;
import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import static com.acme.opsweave.integration.domain.ProblemReadException.Code.*;

/** Zabbix 7.0 event.get contract. At most two bounded calls per page, no retry or fallback. */
public final class ZabbixJsonRpcProblemReader implements ZabbixProblemPort {
    private final URI endpoint;
    private final ZabbixJsonRpcConnector.Transport transport;
    private final ZabbixJsonRpcConnector.SecretSource secrets;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(2);
    public ZabbixJsonRpcProblemReader(URI endpoint, ZabbixJsonRpcConnector.Transport transport,
            ZabbixJsonRpcConnector.SecretSource secrets, Clock clock) {
        if (endpoint == null || !Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
            || endpoint.getUserInfo() != null || endpoint.getFragment() != null) throw new IllegalArgumentException("Invalid configured Zabbix endpoint");
        this.endpoint = endpoint; this.transport = Objects.requireNonNull(transport); this.secrets = Objects.requireNonNull(secrets); this.clock = Objects.requireNonNull(clock);
    }
    @Override public Page read(Connector.SourceContext source, ProblemReadWindow window) {
        Objects.requireNonNull(source); Objects.requireNonNull(window);
        if (window.till() > clock.instant().getEpochSecond()) throw new IllegalArgumentException("Future problem window");
        if (!permits.tryAcquire()) throw new ProblemReadException(SOURCE_BUSY);
        try { return readPage(source, window); }
        finally { permits.release(); }
    }
    private Page readPage(Connector.SourceContext source, ProblemReadWindow window) {
        String lower = window.afterEventId() == null ? "1" : new BigInteger(window.afterEventId()).add(BigInteger.ONE).toString();
        if (new BigInteger(lower).compareTo(new BigInteger("18446744073709551615")) > 0) return new Page(List.of(), null);
        String token;
        try { token = secrets.resolve(source.secretRef()); }
        catch (RuntimeException unavailable) { throw new ProblemReadException(SOURCE_UNAVAILABLE); }
        String fields = "\"eventid\",\"source\",\"object\",\"objectid\",\"clock\",\"ns\",\"value\",\"name\",\"severity\",\"r_eventid\",\"c_eventid\",\"suppressed\"";
        String params = "\"source\":0,\"object\":0,\"value\":1,\"output\":[" + fields + "],\"selectHosts\":[\"hostid\"],"
            + "\"problem_time_from\":" + window.from() + ",\"problem_time_till\":" + window.till()
            + ",\"eventid_from\":\"" + lower + "\",\"sortfield\":\"eventid\",\"sortorder\":\"ASC\",\"limit\":" + window.limit();
        List<Map<String,Object>> problems = exchange(params, token);
        try {
            if (problems.size() > window.limit()) throw new IllegalArgumentException();
            BigInteger previous = new BigInteger(lower).subtract(BigInteger.ONE);
            var recoveries = new LinkedHashSet<String>();
            for (var row : problems) {
                String id = id(row, "eventid");
                if (new BigInteger(id).compareTo(previous) <= 0 || !"0".equals(text(row, "source")) || !"0".equals(text(row, "object"))
                    || !"1".equals(text(row, "value"))) throw new IllegalArgumentException();
                previous = new BigInteger(id);
                // Global-correlation closure is a separate relation; never guess it is an ordinary recovery.
                if (row.containsKey("c_eventid") && !"0".equals(text(row, "c_eventid"))) throw new IllegalArgumentException();
                String recovery = text(row, "r_eventid");
                if (!recovery.equals("0")) recoveries.add(ExternalProblem.positiveId(recovery));
            }
            var recoveryTimes = new HashMap<String,Instant>();
            if (!recoveries.isEmpty()) {
                String ids = recoveries.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
                var rows = exchange("\"source\":0,\"object\":0,\"value\":0,\"eventids\":[" + ids + "],\"output\":[\"eventid\",\"source\",\"object\",\"clock\",\"ns\",\"value\"],\"limit\":" + recoveries.size(), token);
                if (rows.size() > recoveries.size()) throw new IllegalArgumentException();
                for (var row : rows) {
                    String id = id(row, "eventid");
                    if (!recoveries.contains(id) || !"0".equals(text(row, "source")) || !"0".equals(text(row, "object"))
                        || !"0".equals(text(row, "value")) || recoveryTimes.putIfAbsent(id, time(row)) != null) throw new IllegalArgumentException();
                }
            }
            Instant observedAt = clock.instant(); var result = new ArrayList<ExternalProblem>();
            for (var row : problems) {
                Instant occurredAt = time(row);
                if (occurredAt.getEpochSecond() > window.till()) throw new IllegalArgumentException();
                String recovery = text(row, "r_eventid"); recovery = recovery.equals("0") ? null : recovery;
                Instant recovered = recoveryTimes.get(recovery);
                if (recovered != null && recovered.getEpochSecond() < window.from()) throw new IllegalArgumentException();
                if (!(row.get("hosts") instanceof List<?> hosts) || hosts.size() > 20) throw new IllegalArgumentException();
                var hostIds = new ArrayList<String>();
                for (Object host : hosts) {
                    if (!(host instanceof Map<?,?> hostMap) || !(hostMap.get("hostid") instanceof String hostId)) throw new IllegalArgumentException();
                    hostIds.add(ExternalProblem.positiveId(hostId));
                }
                String severity = text(row, "severity"); if (!severity.matches("[0-5]")) throw new IllegalArgumentException();
                String suppressed = text(row, "suppressed"); if (!Set.of("0", "1").contains(suppressed)) throw new IllegalArgumentException();
                result.add(new ExternalProblem(source.tenantId(), source.sourceInstanceId(), id(row, "eventid"), id(row, "objectid"),
                    text(row, "name"), Integer.parseInt(severity), occurredAt, observedAt, hostIds, suppressed.equals("1"), recovery, recovered));
            }
            return new Page(result, result.size() == window.limit() ? result.getLast().problemEventId() : null);
        } catch (ProblemReadException expected) { throw expected; }
        catch (RuntimeException invalid) { throw new ProblemReadException(INVALID_SOURCE_RESPONSE); }
    }
    private List<Map<String,Object>> exchange(String params, String token) {
        String body;
        try { body = transport.exchange(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"event.get\",\"params\":{" + params + "},\"id\":1}", token); }
        catch (RuntimeException failed) { throw new ProblemReadException(SOURCE_FETCH_FAILED); }
        try { return transport.readHostArray(body); }
        catch (RuntimeException invalid) { throw new ProblemReadException(INVALID_SOURCE_RESPONSE); }
    }
    private static String text(Map<String,Object> row, String field) {
        if (!(row.get(field) instanceof String value)) throw new IllegalArgumentException();
        return value;
    }
    private static String id(Map<String,Object> row, String field) { return ExternalProblem.positiveId(text(row, field)); }
    private static Instant time(Map<String,Object> row) {
        String seconds = text(row, "clock"), nanos = text(row, "ns");
        if (!seconds.matches("0|[1-9][0-9]{0,9}") || !nanos.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException();
        return Instant.ofEpochSecond(Long.parseLong(seconds), Integer.parseInt(nanos));
    }
}
