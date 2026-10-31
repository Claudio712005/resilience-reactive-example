package com.clau.resiliencereactiveexample.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.micrometer.Timer;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.micrometer.operator.TimerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

@Slf4j
public final class ResiliencePipeline {

    private final String name;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final Timer timer;
    private final MeterRegistry meterRegistry;

    ResiliencePipeline(String name,
                       CircuitBreaker circuitBreaker,
                       RateLimiter rateLimiter,
                       Bulkhead bulkhead,
                       TimeLimiter timeLimiter,
                       Retry retry,
                       Timer timer,
                       MeterRegistry meterRegistry) {
        this.name = name;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.timeLimiter = timeLimiter;
        this.retry = retry;
        this.timer = timer;
        this.meterRegistry = meterRegistry;
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    public <T> Mono<T> protect(Mono<T> source) {
        return source
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(TimerOperator.of(timer));
    }

    public <T> Flux<T> protect(Flux<T> source) {
        return source
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(TimerOperator.of(timer));
    }

    public <T> Mono<T> protectWithFallback(Mono<T> source, Function<Throwable, Mono<T>> fallback) {
        return protect(source).onErrorResume(error -> {
            recordFallback(error);
            return fallback.apply(error);
        });
    }

    public <T> Mono<T> protectWithValue(Mono<T> source, T fallbackValue) {
        return protectWithFallback(source, error -> Mono.justOrEmpty(fallbackValue));
    }

    private void recordFallback(Throwable error) {
        Counter.builder("catalog.resilience.fallback")
                .description("Calls that fell back after the resilience pipeline rejected or failed them")
                .tag("name", name)
                .tag("exception", error.getClass().getSimpleName())
                .tag("circuit_state", circuitBreaker.getState().name())
                .register(meterRegistry)
                .increment();
        log.warn("resilience fallback engaged name={} circuitState={} exception={} message={}",
                name, circuitBreaker.getState(), error.getClass().getSimpleName(), error.getMessage());
    }
}
