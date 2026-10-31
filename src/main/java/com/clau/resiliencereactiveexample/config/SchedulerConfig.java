package com.clau.resiliencereactiveexample.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class SchedulerConfig {

    public static final String INTEGRATION_SCHEDULER = "integrationScheduler";
    public static final String PERSISTENCE_SCHEDULER = "persistenceScheduler";
    public static final String ENRICHMENT_SCHEDULER = "enrichmentScheduler";

    @Bean(name = INTEGRATION_SCHEDULER, destroyMethod = "dispose")
    public Scheduler integrationScheduler(SchedulerProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.integrationParallelism(),
                properties.integrationQueueCapacity(),
                "catalog-integration",
                60,
                true);
    }

    @Bean(name = PERSISTENCE_SCHEDULER, destroyMethod = "dispose")
    public Scheduler persistenceScheduler(SchedulerProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.persistenceParallelism(),
                properties.integrationQueueCapacity(),
                "catalog-persistence",
                60,
                true);
    }

    @Bean(name = ENRICHMENT_SCHEDULER, destroyMethod = "dispose")
    public Scheduler enrichmentScheduler(SchedulerProperties properties) {
        return Schedulers.newParallel("catalog-enrichment", properties.enrichmentParallelism(), true);
    }
}
