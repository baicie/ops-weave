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
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.infrastructure.ClosedZabbixConnector;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.inventory.application.GetEntityUseCase;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
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
    InMemoryInventoryStore inventoryStore() {
        return new InMemoryInventoryStore();
    }

    @Bean
    GetEntityUseCase getEntityUseCase(AuthorizationService authorization, InMemoryInventoryStore inventory) {
        return new GetEntityUseCase(authorization, inventory);
    }

    @Bean
    InMemoryRawRecordStore rawRecordStore() {
        return new InMemoryRawRecordStore();
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

    @Bean
    IngestZabbixHostsUseCase ingestZabbixHostsUseCase(
        AuthorizationService authorization,
        Connector connector,
        InMemoryInventoryStore inventory,
        InMemoryRawRecordStore rawRecords,
        OpsweaveProperties properties
    ) {
        String mode = normalize(properties.zabbix().mode());
        String dataMode = switch (mode) {
            case "fixture" -> "labeled-fixture";
            case "jsonrpc", "real" -> "zabbix-jsonrpc";
            default -> "closed";
        };
        return new IngestZabbixHostsUseCase(
            authorization,
            connector,
            inventory,
            rawRecords,
            PipelineDefinition.zabbixHostV1(),
            dataMode,
            properties.zabbix().sourceInstanceId(),
            properties.zabbix().secretRef()
        );
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
