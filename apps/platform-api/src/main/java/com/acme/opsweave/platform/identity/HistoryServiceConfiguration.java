package com.acme.opsweave.platform.identity;

import com.acme.opsweave.platform.OpsweaveProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(HistoryServiceSettings.class)
public class HistoryServiceConfiguration {
    @Bean @ConditionalOnProperty(name="opsweave.auth.history-service.enabled", havingValue="true")
    HistoryServiceAccess historyServiceAccess(HistoryServiceSettings settings, OpsweaveProperties platform, @Value("${server.address:}") String address) {
        settings.validate(address); return new HistoryServiceAccess(settings, platform);
    }
    @Bean HistoryServiceFilter historyServiceFilter(ObjectProvider<HistoryServiceAccess> access) { return new HistoryServiceFilter(access); }
    @Bean FilterRegistrationBean<HistoryServiceFilter> historyServiceRegistration(HistoryServiceFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
}
