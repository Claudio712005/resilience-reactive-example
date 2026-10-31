package com.clau.resiliencereactiveexample.config;

import com.clau.resiliencereactiveexample.observability.TraceContextThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @PostConstruct
    public void enableReactorContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TraceContextThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> catalogMeterCustomizer(
            @Value("${spring.application.name}") String applicationName) {
        return registry -> registry.config()
                .commonTags("application", applicationName)
                .meterFilter(catalogHistograms());
    }

    private MeterFilter catalogHistograms() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                String name = id.getName();
                if (!name.startsWith("catalog.") && !name.startsWith("resilience4j.timer")) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .percentiles(0.5, 0.9, 0.95, 0.99)
                        .expiry(Duration.ofMinutes(5))
                        .build()
                        .merge(config);
            }
        };
    }
}
