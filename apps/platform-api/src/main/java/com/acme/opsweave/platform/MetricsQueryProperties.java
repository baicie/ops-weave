package com.acme.opsweave.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsweave.metrics")
public record MetricsQueryProperties(String victoriaUrl) {}
