package com.clau.resiliencereactiveexample.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.TimerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResiliencePipelineFactory {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final RetryRegistry retryRegistry;
    private final TimerRegistry timerRegistry;
    private final MeterRegistry meterRegistry;
    private final Map<String, ResiliencePipeline> pipelines = new ConcurrentHashMap<>();

    public ResiliencePipelineFactory(CircuitBreakerRegistry circuitBreakerRegistry,
                                     RateLimiterRegistry rateLimiterRegistry,
                                     BulkheadRegistry bulkheadRegistry,
                                     TimeLimiterRegistry timeLimiterRegistry,
                                     RetryRegistry retryRegistry,
                                     TimerRegistry timerRegistry,
                                     MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.retryRegistry = retryRegistry;
        this.timerRegistry = timerRegistry;
        this.meterRegistry = meterRegistry;
    }

    public ResiliencePipeline pipeline(String name) {
        return pipelines.computeIfAbsent(name, key -> new ResiliencePipeline(
                key,
                circuitBreakerRegistry.circuitBreaker(key),
                rateLimiterRegistry.rateLimiter(key),
                bulkheadRegistry.bulkhead(key),
                timeLimiterRegistry.timeLimiter(key),
                retryRegistry.retry(key),
                timerRegistry.timer(key),
                meterRegistry));
    }
}
