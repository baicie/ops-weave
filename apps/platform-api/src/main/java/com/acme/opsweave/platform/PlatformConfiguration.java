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
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.ClosedZabbixConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.application.GetEntityUseCase;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpsweaveProperties.class)
public class PlatformConfiguration {
    @Bean
    AuthorizationService authorizationService() {
        return new AuthorizeUseCase();
    }

    @Bean
    PrincipalResolver principalResolver(OpsweaveProperties properties) {
        String mode = normalize(properties.auth().mode());
        if ("oidc".equals(mode)) {
            throw new IllegalStateException("OIDC principal adapter is not implemented; refusing to start with a mock fallback");
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
            PipelineDefinition.zabbixHostV1(),
            dataMode,
            wiring.label(),
            properties.zabbix().sourceInstanceId(),
            properties.zabbix().secretRef(),
            pageSize
        );
    }

    @Bean
    ListMetricDefinitionsUseCase listMetricDefinitionsUseCase(AuthorizationService authorization, InventoryWiring wiring) {
        return new ListMetricDefinitionsUseCase(authorization, wiring.metrics());
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
            wiring.metrics(),
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
