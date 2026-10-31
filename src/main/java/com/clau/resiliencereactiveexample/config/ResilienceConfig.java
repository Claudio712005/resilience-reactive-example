package com.clau.resiliencereactiveexample.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.TimerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final RetryRegistry retryRegistry;

    public ResilienceConfig(CircuitBreakerRegistry circuitBreakerRegistry,
                            RateLimiterRegistry rateLimiterRegistry,
                            BulkheadRegistry bulkheadRegistry,
                            RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.retryRegistry = retryRegistry;
    }

    @Bean
    public TimerRegistry resilienceTimerRegistry(MeterRegistry meterRegistry) {
        return TimerRegistry.ofDefaults(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribeToResilienceEvents() {
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> registerCircuitBreakerLogging(event.getAddedEntry().getName()));
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(circuitBreaker -> registerCircuitBreakerLogging(circuitBreaker.getName()));

        rateLimiterRegistry.getAllRateLimiters().forEach(rateLimiter ->
                rateLimiter.getEventPublisher().onFailure(event ->
                        log.warn("rate limiter rejected call name={} event={}", event.getRateLimiterName(), event.getEventType())));

        bulkheadRegistry.getAllBulkheads().forEach(bulkhead ->
                bulkhead.getEventPublisher().onCallRejected(event ->
                        log.warn("bulkhead rejected call name={} event={}", event.getBulkheadName(), event.getEventType())));

        retryRegistry.getAllRetries().forEach(retry ->
                retry.getEventPublisher().onRetry(event ->
                        log.warn("retry attempt name={} attempt={} waitMs={} cause={}",
                                event.getName(),
                                event.getNumberOfRetryAttempts(),
                                event.getWaitInterval().toMillis(),
                                event.getLastThrowable() == null ? "none" : event.getLastThrowable().getMessage())));
    }

    private void registerCircuitBreakerLogging(String name) {
        circuitBreakerRegistry.circuitBreaker(name).getEventPublisher()
                .onStateTransition(event -> log.warn("circuit breaker state transition name={} from={} to={}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onError(event -> log.debug("circuit breaker recorded error name={} durationMs={} error={}",
                        event.getCircuitBreakerName(),
                        event.getElapsedDuration().toMillis(),
                        event.getThrowable().getClass().getSimpleName()));
    }
}
