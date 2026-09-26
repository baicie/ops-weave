package com.acme.opsweave.platform;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.identity.infrastructure.ClosedPrincipalResolver;
import com.acme.opsweave.identity.infrastructure.DevPrincipalResolver;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.HostPipelineService;
import com.acme.opsweave.integration.application.PipelineReplayService;
import com.acme.opsweave.integration.application.PipelineDraftService;
import com.acme.opsweave.integration.application.SourceScanRunQueryService;
import com.acme.opsweave.integration.application.SourceConnectionCheckService;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.application.ReadZabbixHistoryUseCase;
import com.acme.opsweave.integration.api.ZabbixHistoryPort;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.ClosedZabbixConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHistoryReader;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcHistoryReader;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.application.GetEntityUseCase;
import com.acme.opsweave.inventory.application.BrowseEntitiesUseCase;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.EntityExistence;
import com.acme.opsweave.telemetry.api.MetricQueryPort;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.infrastructure.ClosedMetricQuery;
import com.acme.opsweave.platform.telemetry.VictoriaMetricsQueryAdapter;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OpsweaveProperties.class, MetricsQueryProperties.class})
public class PlatformConfiguration {
    @Bean
    com.acme.opsweave.aicontrol.application.AiInsightService aiInsightService(AuthorizationService authorization,
            com.acme.opsweave.incident.application.IncidentService incidents, com.acme.opsweave.aicontrol.application.ToolGateway gateway, InventoryWiring wiring) {
        return new com.acme.opsweave.aicontrol.application.AiInsightService(authorization, incidents, gateway, wiring.toolReads(), wiring.insights(), new com.acme.opsweave.platform.ai.InsightJson(), Clock.systemUTC());
    }
    @Bean(destroyMethod = "close")
    com.acme.opsweave.platform.ai.ToolExecutor toolExecutor() { return new com.acme.opsweave.platform.ai.ToolExecutor(); }

    @Bean
    com.acme.opsweave.aicontrol.application.ToolGateway toolGateway(AuthorizationService authorization,
            com.acme.opsweave.incident.application.IncidentService incidents, QueryMetricSeriesUseCase metrics, InventoryWiring wiring) {
        return new com.acme.opsweave.aicontrol.application.ToolGateway(authorization, incidents, metrics, wiring.metrics(), wiring.toolReads(),
            com.acme.opsweave.platform.ai.ToolJson::bytes, Clock.systemUTC());
    }

    @Bean
    AuthorizationService authorizationService() {
        return new AuthorizeUseCase();
    }

    @Bean
    PrincipalResolver principalResolver(OpsweaveProperties properties) {
        String mode = normalize(properties.auth().mode());
        if ("oidc".equals(mode)) {
            return new ClosedPrincipalResolver(); // Browser sessions and scoped internal delegations have a separate verified boundary.
        }
        if ("dev".equals(mode)) {
            var dev = properties.auth().dev();
            Set<Permission> permissions = Arrays.stream(dev.permissions().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Permission::fromWire)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            ResourceScope scope = scope(dev, new TenantId(dev.tenant()));
            return new DevPrincipalResolver(
                dev.token(),
                new SubjectId(dev.subject()),
                new TenantId(dev.tenant()),
                Set.copyOf(permissions),
                scope
            );
        }
        return new ClosedPrincipalResolver();
    }

    @Bean
    InventoryWiring inventoryWiring(OpsweaveProperties properties) {
        return InventoryWiring.open(properties);
    }

    @Bean
    InventoryQuery inventoryQuery(InventoryWiring wiring) {
        return wiring.query();
    }

    @Bean
    InventoryWritePort inventoryWritePort(InventoryWiring wiring) {
        return wiring.writer();
    }

    @Bean
    GetEntityUseCase getEntityUseCase(AuthorizationService authorization, InventoryQuery inventory) {
        return new GetEntityUseCase(authorization, inventory);
    }

    @Bean
    IngestZabbixHostsUseCase ingestZabbixHostsUseCase(
        AuthorizationService authorization,
        Connector connector,
        InventoryWiring wiring,
        OpsweaveProperties properties
    ) {
        String mode = normalize(properties.zabbix().mode());
        String dataMode = switch (mode) {
            case "fixture" -> "labeled-fixture";
            case "jsonrpc", "real" -> "zabbix-jsonrpc";
            default -> "closed";
        };
        int pageSize = properties.zabbix().pageSize() == 0 ? 100 : properties.zabbix().pageSize();
        return new IngestZabbixHostsUseCase(
            authorization,
            connector,
            wiring.writer(),
            wiring.rawRecords(),
            wiring.syncRuns(),
            wiring.pipelines(),
            dataMode,
            wiring.label(),
            properties.zabbix().sourceInstanceId(),
            properties.zabbix().secretRef(),
            pageSize
        );
    }

    @Bean
    PipelineDraftService pipelineDraftService(AuthorizationService authorization, InventoryWiring wiring, OpsweaveProperties properties) {
        return new PipelineDraftService(authorization, wiring.drafts(), properties.zabbix().sourceInstanceId(), Clock.systemUTC());
    }

    @Bean
    BrowseEntitiesUseCase browseEntitiesUseCase(AuthorizationService authorization, InventoryWiring wiring) {
        return new BrowseEntitiesUseCase(authorization, wiring.query());
    }

    @Bean
    com.acme.opsweave.incident.application.IncidentService incidentService(AuthorizationService authorization, InventoryWiring wiring) {
        return new com.acme.opsweave.incident.application.IncidentService(authorization, wiring.incidents(), Clock.systemUTC());
    }

    @Bean
    com.acme.opsweave.incident.application.ProblemHistoryService problemHistoryService(com.acme.opsweave.incident.application.IncidentService incidents,InventoryWiring wiring) {
        return new com.acme.opsweave.incident.application.ProblemHistoryService(incidents,wiring.problemHistory(),Clock.systemUTC());
    }

    @Bean
    com.acme.opsweave.integration.application.IngestZabbixProblemsUseCase ingestZabbixProblemsUseCase(
            com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase reader, InventoryWiring wiring) {
        return new com.acme.opsweave.integration.application.IngestZabbixProblemsUseCase(reader, wiring.incidents(), Clock.systemUTC());
    }

    @Bean
    PipelineReplayService pipelineReplayService(AuthorizationService authorization, InventoryWiring wiring,
            HostPipelineService evaluator, OpsweaveProperties properties) {
        return new PipelineReplayService(authorization, wiring.replays(), evaluator, properties.zabbix().sourceInstanceId(), Clock.systemUTC());
    }

    @Bean
    HostPipelineService hostPipelineService(AuthorizationService authorization, InventoryWiring wiring, OpsweaveProperties properties) {
        return new HostPipelineService(authorization, wiring.pipelines(), wiring.rawReader(), wiring.syncRuns(),
            properties.zabbix().sourceInstanceId());
    }

    @Bean
    SourceScanRunQueryService sourceScanRunQueryService(AuthorizationService authorization, InventoryWiring wiring,
            OpsweaveProperties properties) {
        return new SourceScanRunQueryService(authorization, wiring.syncRuns(), wiring.pipelines(),
            properties.zabbix().sourceInstanceId());
    }

    @Bean
    SourceConnectionCheckService sourceConnectionCheckService(AuthorizationService authorization, Connector connector,
            InventoryWiring wiring, OpsweaveProperties properties) {
        String mode = normalize(properties.zabbix().mode());
        String dataMode = switch (mode) {
            case "fixture" -> "labeled-fixture";
            case "jsonrpc", "real" -> "zabbix-jsonrpc";
            default -> "closed";
        };
        return new SourceConnectionCheckService(authorization, connector, wiring.sourceChecks(),
            properties.zabbix().sourceInstanceId(), dataMode, properties.zabbix().secretRef(), Clock.systemUTC());
    }

    @Bean
    ReadZabbixHistoryUseCase readZabbixHistoryUseCase(AuthorizationService authorization, InventoryWiring wiring,
            OpsweaveProperties properties, JacksonZabbixTransport transport, EnvSecretSource secrets) {
        String mode = normalize(properties.zabbix().mode());
        ZabbixHistoryPort reader;
        String dataMode;
        switch (mode) {
            case "fixture" -> {
                reader = new FixtureZabbixHistoryReader();
                dataMode = "labeled-fixture";
            }
            case "jsonrpc", "real" -> {
                String url = properties.zabbix().url();
                if (url == null || url.isBlank()) throw new IllegalStateException("OPSWEAVE_ZABBIX_URL is required for jsonrpc mode");
                reader = new ZabbixJsonRpcHistoryReader(URI.create(url), transport, secrets,
                    ClasspathMappingCatalog.load(PlatformConfiguration.class.getClassLoader()));
                dataMode = "zabbix-jsonrpc";
            }
            default -> {
                reader = (source, binding, window) -> { throw new HistoryReadException(HistoryReadException.Code.SOURCE_UNAVAILABLE); };
                dataMode = "closed";
            }
        }
        return new ReadZabbixHistoryUseCase(authorization, wiring.metrics(), reader,
            properties.zabbix().sourceInstanceId(), properties.zabbix().secretRef(), dataMode, Clock.systemUTC());
    }

    @Bean
    com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase readZabbixProblemsUseCase(AuthorizationService authorization,
            OpsweaveProperties properties, JacksonZabbixTransport transport, EnvSecretSource secrets) {
        var clock = Clock.systemUTC(); String mode = normalize(properties.zabbix().mode());
        com.acme.opsweave.integration.api.ZabbixProblemPort port;
        String dataMode;
        if (mode.equals("fixture")) {
            port = new com.acme.opsweave.integration.infrastructure.FixtureZabbixProblemReader(clock); dataMode = "labeled-fixture";
        } else if (Set.of("jsonrpc", "real").contains(mode)) {
            port = new com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcProblemReader(URI.create(properties.zabbix().url()), transport, secrets, clock);
            dataMode = "zabbix-jsonrpc";
        } else {
            port = (source, window) -> { throw new com.acme.opsweave.integration.domain.ProblemReadException(com.acme.opsweave.integration.domain.ProblemReadException.Code.SOURCE_UNAVAILABLE); };
            dataMode = "closed";
        }
        return new com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase(authorization, port,
            properties.zabbix().sourceInstanceId(), properties.zabbix().secretRef(), dataMode, clock);
    }

    @Bean
    ListMetricDefinitionsUseCase listMetricDefinitionsUseCase(AuthorizationService authorization, InventoryWiring wiring) {
        return new ListMetricDefinitionsUseCase(authorization, wiring.metrics());
    }

    @Bean
    MetricQueryPort metricQueryPort(MetricsQueryProperties metrics) {
        String url = metrics.victoriaUrl();
        if (url == null || url.isBlank()) return new ClosedMetricQuery();
        return new VictoriaMetricsQueryAdapter(URI.create(url));
    }

    @Bean
    QueryMetricSeriesUseCase queryMetricSeriesUseCase(AuthorizationService authorization, InventoryWiring wiring, MetricQueryPort metricQueryPort) {
        EntityExistence entities = (tenantId, entityId) -> wiring.query().find(tenantId, entityId).isPresent();
        return new QueryMetricSeriesUseCase(authorization, entities, wiring.metrics(), metricQueryPort, Clock.systemUTC());
    }

    @Bean
    IngestZabbixItemsUseCase ingestZabbixItemsUseCase(
        AuthorizationService authorization,
        OpsweaveProperties properties,
        JacksonZabbixTransport transport,
        EnvSecretSource secrets,
        InventoryWiring wiring
    ) {
        String mode = normalize(properties.zabbix().mode());
        String dataMode = switch (mode) {
            case "fixture" -> "labeled-fixture";
            case "jsonrpc", "real" -> "zabbix-jsonrpc";
            default -> "closed";
        };
        int pageSize = properties.zabbix().pageSize() == 0 ? 100 : properties.zabbix().pageSize();
        var mappings = ClasspathMappingCatalog.load(PlatformConfiguration.class.getClassLoader());
        if (mappings.isEmpty() && !"closed".equals(mode)) {
            throw new IllegalStateException("Mapping catalog is empty");
        }
        return new IngestZabbixItemsUseCase(
            authorization,
            itemConnector(properties, transport, secrets),
            mappings,
            wiring.writer(),
            wiring.itemWrites(),
            wiring.rawRecords(),
            wiring.syncRuns(),
            dataMode,
            wiring.label(),
            properties.zabbix().sourceInstanceId(),
            properties.zabbix().secretRef(),
            pageSize
        );
    }

    @Bean
    Connector zabbixConnector(OpsweaveProperties properties, JacksonZabbixTransport transport, EnvSecretSource secrets) {
        String mode = normalize(properties.zabbix().mode());
        if ("fixture".equals(mode)) {
            return new FixtureZabbixHostConnector();
        }
        if ("jsonrpc".equals(mode) || "real".equals(mode)) {
            String url = properties.zabbix().url();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("OPSWEAVE_ZABBIX_URL is required for jsonrpc mode");
            }
            return new ZabbixJsonRpcConnector(URI.create(url), transport, secrets);
        }
        return new ClosedZabbixConnector();
    }

    private static Connector itemConnector(
        OpsweaveProperties properties,
        JacksonZabbixTransport transport,
        EnvSecretSource secrets
    ) {
        String mode = normalize(properties.zabbix().mode());
        if ("fixture".equals(mode)) {
            return new FixtureZabbixItemConnector();
        }
        if ("jsonrpc".equals(mode) || "real".equals(mode)) {
            String url = properties.zabbix().url();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("OPSWEAVE_ZABBIX_URL is required for jsonrpc mode");
            }
            return new ZabbixJsonRpcItemConnector(URI.create(url), transport, secrets);
        }
        return new ClosedZabbixConnector();
    }

    private static ResourceScope scope(OpsweaveProperties.Auth.Dev dev, TenantId tenantId) {
        String ids = dev.entityIds();
        if (ids == null || ids.isBlank()) {
            return ResourceScope.tenantWide();
        }
        Set<ResourceRef> refs = Arrays.stream(ids.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(EntityId::parse)
            .map(id -> ResourceRef.entity(tenantId, id))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return ResourceScope.of(Set.copyOf(refs));
    }

    private static String normalize(String value) {
        return value == null ? "closed" : value.trim().toLowerCase(Locale.ROOT);
    }
}
