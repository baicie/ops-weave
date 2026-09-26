package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

/** Fixed read-only native executors. A model never provides identity, policy, source URLs or database queries. */
public final class ToolGateway {
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final QueryMetricSeriesUseCase metrics;
    private final MetricDefinitionStore definitions;
    private final ToolReadStore store;
    private final EvidenceEncoding encoding;
    private final Clock clock;
    public ToolGateway(AuthorizationService authorization, IncidentService incidents, QueryMetricSeriesUseCase metrics,
            MetricDefinitionStore definitions, ToolReadStore store, EvidenceEncoding encoding, Clock clock) {
        this.authorization = authorization; this.incidents = incidents; this.metrics = metrics; this.definitions = definitions;
        this.store = store; this.encoding = encoding; this.clock = clock;
    }
    public ToolReadSession create(Principal p, UUID incidentId, ToolWindow window) {
        require(p, ResourceRef.incident(p.tenantId(), incidentId), Permission.AI_DIAGNOSE);
        require(p, new ResourceRef(p.tenantId(), "evidence", "*"), Permission.EVIDENCE_READ);
        var incident = incidents.get(p, incidentId);
        if (incident.merged()) throw new ToolFailure(INPUT_CHANGED);
        if (incident.entityIds().size() > ToolReadSession.MAX_ENTITIES) throw new ToolFailure(READ_LIMIT);
        Instant now = clock.instant();
        var session = new ToolReadSession(UUID.randomUUID(), p.tenantId(), p.subjectId(), incidentId, incident.incident().version(),
            incident.entityIds(), window, now, now.plusSeconds(60), 0);
        store.create(session); return session;
    }
    public PlatformEvidence incident(Principal p, UUID sessionId, UUID incidentId) {
        return execute(p, sessionId, incidentId, "incident.get@2.0.0", (s, incident) -> {
            var data = new LinkedHashMap<String,Object>(); var h = incident.incident();
            data.put("title", h.title()); data.put("status", h.status().name()); data.put("severity", h.severity()); data.put("version", h.version());
            data.put("problems", incident.problems().stream().map(problem -> {
                var o = problem.observation(); var row = new LinkedHashMap<String,Object>();
                row.put("sourceInstanceId", o.sourceInstanceId()); row.put("sourceContract", "zabbix-7.0-event-v1"); row.put("dataMode", problem.dataMode());
                row.put("problemEventId", o.problemEventId()); row.put("title", o.title()); row.put("state", o.state().name()); row.put("severity", o.severity());
                row.put("occurredAt", o.occurredAt().toString()); row.put("observedAt", o.observedAt().toString()); row.put("lastReceivedAt", problem.lastReceivedAt().toString());
                row.put("suppressed", o.suppressed()); row.put("recoveryEventId", o.recoveryEventId() == null ? "" : o.recoveryEventId());
                row.put("recoveredAt", o.recoveredAt() == null ? "" : o.recoveredAt().toString());
                row.put("overlapsWindow", !o.occurredAt().isAfter(s.window().to()) && (o.recoveredAt() == null || !o.recoveredAt().isBefore(s.window().from())));
                return row;
            }).toList());
            var warnings = new TreeSet<>(Set.of("CURRENT_KNOWLEDGE_ONLY", "LOGS_NOT_CONNECTED", "CHANGES_NOT_CONNECTED"));
            if (incident.hasUnmappedHosts()) warnings.add("ENTITY_MAPPING_MISSING");
            if (incident.problems().stream().anyMatch(v -> v.observation().state().name().equals("RECOVERY_UNKNOWN"))) warnings.add("RECOVERY_EVENT_UNAVAILABLE");
            var modes = incident.problems().stream().map(IncidentRecord.Problem::dataMode).distinct().sorted().toList();
            Instant observed = incident.problems().stream().map(v -> v.observation().observedAt()).max(Comparator.naturalOrder()).orElseThrow();
            return evidence(s, "incident", h.title() + "; current human status " + h.status() + "; severity " + h.severity()
                + "; linked problems " + incident.problems().size() + ". Source recovery does not imply human resolution.", observed, modes, warnings, data);
        });
    }
    public PlatformEvidence metric(Principal p, UUID sessionId, UUID incidentId, String metricKey, ToolWindow window, int maxPoints) {
        return execute(p, sessionId, incidentId, "metric.summary@2.0.0", (s, incident) -> {
            if (!s.window().equals(window) || metricKey == null || !metricKey.matches("[A-Za-z0-9_.:/%\\-]{1,128}") || maxPoints < 1 || maxPoints > 500)
                throw new ToolFailure(INVALID_REQUEST);
            require(p, ResourceRef.metric(p.tenantId(), metricKey), Permission.METRIC_READ);
            var warnings = new TreeSet<>(Set.of("CURRENT_KNOWLEDGE_ONLY", "METRIC_INGEST_TIME_UNAVAILABLE"));
            if (incident.hasUnmappedHosts()) warnings.add("ENTITY_MAPPING_MISSING");
            var definition = definitions.find(p.tenantId(), metricKey).orElse(null);
            var data = new LinkedHashMap<String,Object>(); data.put("metricKey", metricKey);
            var sources = new TreeSet<String>(); var entities = new ArrayList<Map<String,Object>>();
            int points = 0;
            if (definition == null) warnings.add("METRIC_NOT_FOUND");
            else {
                data.put("definitionVersion", definition.version()); data.put("unit", definition.unit()); data.put("metricType", definition.metricType().name());
                if (!definition.metricType().name().equals("GAUGE")) warnings.add("UNSUPPORTED_METRIC_KIND");
                else {
                    if (s.entityIds().isEmpty()) warnings.add("NO_ASSOCIATED_ENTITIES");
                    if (!s.entityIds().isEmpty() && maxPoints < s.entityIds().size()) throw new ToolFailure(INVALID_REQUEST);
                    int each = s.entityIds().isEmpty() ? maxPoints : maxPoints / s.entityIds().size();
                    for (var entity : s.entityIds().stream().sorted(Comparator.comparing(v -> v.value().toString())).toList()) {
                        deadline(s);
                        var result = metrics.query(p, entity, metricKey, window.from().getEpochSecond(), window.to().getEpochSecond(), each);
                        switch (result.kind()) {
                            case FORBIDDEN -> throw new ToolFailure(FORBIDDEN);
                            case UNAVAILABLE -> throw new ToolFailure(UNAVAILABLE);
                            case INVALID -> throw new ToolFailure(INVALID_REQUEST);
                            case NOT_FOUND -> { warnings.add("METRIC_OR_ENTITY_NOT_FOUND"); continue; }
                            case AVAILABLE -> { }
                        }
                        var page = result.page();
                        if (page.status().partial()) warnings.add("METRIC_POINTS_PARTIAL");
                        if (page.status().kind() == MetricSeriesResult.Status.Kind.NO_DATA) warnings.add("METRIC_NO_DATA");
                        if (page.status().kind() == MetricSeriesResult.Status.Kind.STALE) warnings.add("METRIC_STALE");
                        var rows = new ArrayList<Map<String,Object>>();
                        for (var series : page.series()) {
                            if (!Set.of("labeled-fixture", "zabbix-jsonrpc").contains(series.dataMode()) || series.points().isEmpty()) throw new ToolFailure(UNAVAILABLE);
                            sources.add(series.dataMode()); points += series.points().size(); if (points > maxPoints) throw new ToolFailure(READ_LIMIT);
                            var row = summary(series); rows.add(row);
                        }
                        if (rows.size() > 50) throw new ToolFailure(READ_LIMIT);
                        var row = new LinkedHashMap<String,Object>(); row.put("entityId", entity.value().toString()); row.put("status", page.status().kind().name()); row.put("series", rows); entities.add(row);
                    }
                }
            }
            // A catalog edit during a multi-entity read must not relabel old samples with a new definition.
            if (!Objects.equals(definition, definitions.find(p.tenantId(), metricKey).orElse(null))) throw new ToolFailure(INPUT_CHANGED);
            data.put("entities", entities); data.put("sampleCount", points); data.put("maxPoints", maxPoints);
            if (sources.isEmpty()) sources.add("unknown");
            // Sample timestamps do not establish when the platform first knew them. Current knowledge is explicit.
            return evidence(s, "metric", "Metric " + metricKey + "; returned samples " + points + "; linked entities " + s.entityIds().size()
                + ". Gauge summaries remain separate by entity, source and dimensions. Missing samples are not zeros; historical ingestion time is unavailable.",
                clock.instant(), List.copyOf(sources), warnings, data);
        });
    }
    public PlatformEvidence getEvidence(Principal p, UUID id) {
        try {
            require(p, new ResourceRef(p.tenantId(), "evidence", id.toString()), Permission.EVIDENCE_READ);
            var found=store.find(p.tenantId(),id);
            if(found.isEmpty())store.retired(p.tenantId(),id).ifPresent(m->RetiredReadAccess.reject(p,m,authorization,incidents));
            var value = found.orElseThrow(() -> new ToolFailure(NOT_FOUND));
            var current = incidents.get(p, value.incidentId()); // Reorganizations invalidate snapshots of the old association set.
            if (current.merged() || current.organization().version() > value.incidentVersion()) throw new ToolFailure(INPUT_CHANGED);
            for (var entity : value.entityIds()) require(p, ResourceRef.entity(p.tenantId(), entity), Permission.ENTITY_READ);
            if (value.kind().equals("metric")) require(p, ResourceRef.metric(p.tenantId(), (String) value.data().get("metricKey")), Permission.METRIC_READ);
            value.checkLive(clock.instant()); int bytes = size(value);
            store.auditAccess(p.tenantId(), p.subjectId(), id, "OK", bytes, clock.instant()); return value;
        } catch (RuntimeException error) {
            var code = code(error); store.auditAccess(p.tenantId(), p.subjectId(), id, code.name(), 0, clock.instant()); throw new ToolFailure(code);
        }
    }
    public PlatformEvidence recheck(Principal p, UUID sessionId, UUID evidenceId) {
        var call = store.begin(p.tenantId(), p.subjectId(), sessionId, "evidence.get@2.0.0", clock.instant());
        try {
            require(p, ResourceRef.incident(p.tenantId(), call.session().incidentId()), Permission.AI_DIAGNOSE);
            current(p, call.session());
            var value = getEvidence(p, evidenceId);
            if (!value.sessionId().equals(sessionId) || !value.incidentId().equals(call.session().incidentId())) throw new ToolFailure(FORBIDDEN);
            current(p, call.session());
            deadline(call.session()); store.complete(call, value, size(value), clock.instant()); return value;
        } catch (RuntimeException error) {
            var code = code(error); store.fail(call, code, clock.instant()); throw new ToolFailure(code);
        }
    }
    private PlatformEvidence execute(Principal p, UUID sessionId, UUID incidentId, String tool, Read read) {
        var call = store.begin(p.tenantId(), p.subjectId(), sessionId, tool, clock.instant());
        try {
            var s = call.session(); if (!s.incidentId().equals(incidentId)) throw new ToolFailure(FORBIDDEN);
            require(p, ResourceRef.incident(p.tenantId(), incidentId), Permission.AI_DIAGNOSE);
            require(p, new ResourceRef(p.tenantId(), "evidence", "*"), Permission.EVIDENCE_READ);
            var incident = current(p, s); var value = read.read(s, incident);
            current(p, s); deadline(s);
            if (!clock.instant().isBefore(call.startedAt().plusSeconds(15))) throw new ToolFailure(DEADLINE);
            int bytes = size(value); store.complete(call, value, bytes, clock.instant()); return value;
        } catch (RuntimeException error) {
            var code = code(error); store.fail(call, code, clock.instant()); throw new ToolFailure(code);
        }
    }
    private IncidentRecord current(Principal p, ToolReadSession session) {
        var record = incidents.get(p, session.incidentId());
        if (record.incident().version() != session.incidentVersion() || !record.entityIds().equals(session.entityIds())) throw new ToolFailure(INPUT_CHANGED);
        return record;
    }
    private PlatformEvidence evidence(ToolReadSession s, String kind, String summary, Instant observed, List<String> modes,
            Collection<String> warnings, Map<String,Object> data) {
        Instant captured = clock.instant();
        return new PlatformEvidence(UUID.randomUUID(), s.id(), s.tenantId(), s.incidentId(), s.incidentVersion(), s.entityIds(), kind, summary,
            s.window(), observed, captured, captured.plus(Duration.ofHours(24)), modes, List.copyOf(warnings), data);
    }
    private static Map<String,Object> summary(MetricSeriesResult.MetricSeries s) {
        var points = s.points(); var values = points.stream().map(MetricSeriesResult.Sample::value).toList();
        var sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.ofEntries(Map.entry("sourceInstanceId", s.sourceInstanceId()), Map.entry("dataMode", s.dataMode()), Map.entry("externalItemId", s.externalItemId()),
            Map.entry("mappingRevision", s.mappingRevision()), Map.entry("unit", s.unit()), Map.entry("dimensions", s.dimensions()), Map.entry("count", points.size()),
            Map.entry("min", values.stream().min(Comparator.naturalOrder()).orElseThrow().toPlainString()), Map.entry("max", values.stream().max(Comparator.naturalOrder()).orElseThrow().toPlainString()),
            Map.entry("mean", sum.divide(BigDecimal.valueOf(points.size()), MathContext.DECIMAL64).toPlainString()), Map.entry("last", points.getLast().value().toPlainString()),
            Map.entry("firstAt", Instant.ofEpochMilli(points.getFirst().timestampMillis()).toString()), Map.entry("lastAt", Instant.ofEpochMilli(points.getLast().timestampMillis()).toString()));
    }
    private int size(PlatformEvidence value) { int bytes = encoding.bytes(value); if (bytes < 1 || bytes > 30000) throw new ToolFailure(READ_LIMIT); return bytes; }
    private void deadline(ToolReadSession s) { if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(s.deadlineAt())) throw new ToolFailure(DEADLINE); }
    private void require(Principal p, ResourceRef ref, Permission permission) { if (authorization.authorize(p, ref, permission).denied()) throw new ToolFailure(FORBIDDEN); }
    private static ToolFailure.Code code(RuntimeException error) {
        if (error instanceof ToolFailure failure) return failure.code();
        if (error instanceof IncidentFailure incident) return switch (incident.code()) {
            case FORBIDDEN -> FORBIDDEN; case NOT_FOUND -> NOT_FOUND; case READ_LIMIT -> READ_LIMIT; default -> UNAVAILABLE;
        };
        return UNAVAILABLE;
    }
    @FunctionalInterface private interface Read { PlatformEvidence read(ToolReadSession session, IncidentRecord incident); }
}
