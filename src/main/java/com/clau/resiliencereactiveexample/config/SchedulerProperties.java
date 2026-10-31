package com.clau.resiliencereactiveexample.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.schedulers")
public record SchedulerProperties(
        int integrationParallelism,
        int integrationQueueCapacity,
        int persistenceParallelism,
        int enrichmentParallelism) {

    public SchedulerProperties {
        int cores = Runtime.getRuntime().availableProcessors();
        if (integrationParallelism <= 0) {
            integrationParallelism = cores * 4;
        }
        if (integrationQueueCapacity <= 0) {
            integrationQueueCapacity = 1_000;
        }
        if (persistenceParallelism <= 0) {
            persistenceParallelism = cores * 2;
        }
        if (enrichmentParallelism <= 0) {
            enrichmentParallelism = cores;
        }
    }
}
