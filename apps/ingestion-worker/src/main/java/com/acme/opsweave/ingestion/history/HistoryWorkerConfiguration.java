package com.acme.opsweave.ingestion.history;

import com.acme.opsweave.integration.application.IngestMetricHistoryUseCase;
import com.acme.opsweave.integration.domain.HistoryCheckpoint;
import com.acme.opsweave.integration.domain.HistoryPollPolicy;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name = "opsweave.history.enabled", havingValue = "true")
@EnableConfigurationProperties({HistoryWorkerProperties.class, HistoryServiceClientSettings.class})
@EnableScheduling
public class HistoryWorkerConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource historyDataSource(HistoryWorkerProperties p, @org.springframework.beans.factory.annotation.Value("${opsweave.history.auth-mode:dev}") String authMode) {
        HistoryCheckpoint.initial(p.initialFrom());
        new HistoryPollPolicy(p.stepSeconds(), p.overlapSeconds(), p.delaySeconds(), p.maxPages());
        new com.acme.opsweave.sharedkernel.TenantId(p.tenant());
        if (!java.util.Set.of("dev", "client-credentials").contains(authMode) || (authMode.equals("dev") && !"tenant-demo".equals(p.tenant())) || p.pollMillis() < 1000 || p.pollMillis() > 3600000
            || p.jdbcUrl() == null || !p.jdbcUrl().matches("jdbc:postgresql://(127\\.0\\.0\\.1|\\[::1\\]):[0-9]+/[a-zA-Z0-9_]+")
            || p.jdbcUser() == null || p.jdbcUser().isBlank()) {
            throw new IllegalArgumentException("History worker requires explicit loopback development configuration");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("history-checkpoints");
        dataSource.setJdbcUrl(p.jdbcUrl()); dataSource.setUsername(p.jdbcUser()); dataSource.setPassword(p.jdbcPassword());
        dataSource.setMaximumPoolSize(2); dataSource.setMinimumIdle(0); dataSource.setConnectionTimeout(3000);
        return dataSource;
    }

    @Bean
    IngestMetricHistoryUseCase ingestMetricHistoryUseCase(HistoryWorkerProperties p, HikariDataSource historyDataSource, HistoryServiceClientSettings service,
            @org.springframework.beans.factory.annotation.Value("${opsweave.history.auth-mode:dev}") String authMode) {
        if (authMode.equals("client-credentials") && p.platformToken() != null && !p.platformToken().isEmpty()) throw new IllegalArgumentException("Service mode must not configure a dev credential");
        var source = authMode.equals("client-credentials")
            ? new PlatformHistoryReader(URI.create(p.platformUrl()), new ClientCredentialsAuthorization(service), p.expectedDataMode(), service.loopbackTest())
            : new PlatformHistoryReader(URI.create(p.platformUrl()), p.platformToken(), p.expectedDataMode());
        var sink = new VictoriaMetricsWriter(URI.create(p.victoriaUrl()));
        sink.verifyConfiguration();
        var checkpoints = new PostgresHistoryCheckpointStore(historyDataSource);
        checkpoints.initialize();
        return new IngestMetricHistoryUseCase(source, sink, checkpoints,
            new HistoryPollPolicy(p.stepSeconds(), p.overlapSeconds(), p.delaySeconds(), p.maxPages()), Clock.systemUTC());
    }

    @Bean
    HistoryPoller historyPoller(HistoryWorkerProperties properties, IngestMetricHistoryUseCase useCase) {
        return new HistoryPoller(properties, useCase);
    }
}
