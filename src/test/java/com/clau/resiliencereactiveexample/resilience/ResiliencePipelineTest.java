package com.clau.resiliencereactiveexample.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.TimerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResiliencePipelineTest {

    private static final String NAME = "unit-under-test";

    @Test
    void shouldRetryWithExponentialBackoffUntilTheCallSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .retry(RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                                Duration.ofMillis(10), 2.0d, 0.5d))
                        .retryExceptions(IOException.class)
                        .build()));

        Mono<String> flaky = Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.error(new IOException("downstream warming up"))
                : Mono.just("recovered"));

        StepVerifier.create(pipeline.protect(flaky))
                .expectNext("recovered")
                .verifyComplete();

        assertThat(attempts).hasValue(3);
    }

    @Test
    void shouldNotRetryExceptionsFlaggedAsIgnored() {
        AtomicInteger attempts = new AtomicInteger();
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .retry(RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(5))
                        .retryExceptions(IOException.class)
                        .ignoreExceptions(IllegalStateException.class)
                        .build()));

        Mono<String> failing = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("business rejection"));
        });

        StepVerifier.create(pipeline.protect(failing))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(attempts).hasValue(1);
    }

    @Test
    void shouldFailFastOnceTheCircuitBreakerOpens() {
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .circuitBreaker(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(IOException.class)
                        .build())
                .retry(RetryConfig.custom().maxAttempts(1).build()));

        for (int i = 0; i < 4; i++) {
            StepVerifier.create(pipeline.protect(Mono.error(new IOException("boom"))))
                    .expectError(IOException.class)
                    .verify();
        }

        assertThat(pipeline.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        StepVerifier.create(pipeline.protect(Mono.just("never reached")))
                .expectError(CallNotPermittedException.class)
                .verify();
    }

    @Test
    void shouldInterruptCallsSlowerThanTheTimeLimiter() {
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .timeLimiter(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(60))
                        .cancelRunningFuture(true)
                        .build())
                .retry(RetryConfig.custom().maxAttempts(1).build()));

        StepVerifier.create(pipeline.protect(Mono.delay(Duration.ofSeconds(2)).thenReturn("too late")))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void shouldRejectCallsAboveTheBulkheadLimit() {
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .bulkhead(BulkheadConfig.custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build())
                .timeLimiter(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(30)).build())
                .retry(RetryConfig.custom().maxAttempts(1).build()));

        var holding = pipeline.protect(Mono.never()).subscribe();
        try {
            StepVerifier.create(pipeline.protect(Mono.just("rejected")))
                    .expectError(BulkheadFullException.class)
                    .verify(Duration.ofSeconds(5));
        } finally {
            holding.dispose();
        }
    }

    @Test
    void shouldRejectCallsAboveTheRateLimiterQuota() {
        ResiliencePipeline pipeline = pipeline(builder -> builder
                .rateLimiter(RateLimiterConfig.custom()
                        .limitForPeriod(2)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build())
                .retry(RetryConfig.custom().maxAttempts(1).build()));

        StepVerifier.create(pipeline.protect(Mono.just("first"))).expectNext("first").verifyComplete();
        StepVerifier.create(pipeline.protect(Mono.just("second"))).expectNext("second").verifyComplete();
        StepVerifier.create(pipeline.protect(Mono.just("third")))
                .expectError(RequestNotPermitted.class)
                .verify();
    }

    @Test
    void shouldRecordFallbackMetricWhenTheProtectedCallFails() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResiliencePipeline pipeline = pipeline(meterRegistry, builder -> builder
                .retry(RetryConfig.custom().maxAttempts(1).build()));

        StepVerifier.create(pipeline.protectWithFallback(
                        Mono.error(new IOException("downstream down")),
                        error -> Mono.just("degraded")))
                .expectNext("degraded")
                .verifyComplete();

        double fallbacks = meterRegistry.find("catalog.resilience.fallback").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
        assertThat(fallbacks).isEqualTo(1.0d);
    }

    private ResiliencePipeline pipeline(java.util.function.UnaryOperator<PipelineBuilder> customizer) {
        return pipeline(new SimpleMeterRegistry(), customizer);
    }

    private ResiliencePipeline pipeline(MeterRegistry meterRegistry,
                                        java.util.function.UnaryOperator<PipelineBuilder> customizer) {
        PipelineBuilder builder = customizer.apply(new PipelineBuilder());
        ResiliencePipelineFactory factory = new ResiliencePipelineFactory(
                CircuitBreakerRegistry.of(builder.circuitBreakerConfig),
                RateLimiterRegistry.of(builder.rateLimiterConfig),
                BulkheadRegistry.of(builder.bulkheadConfig),
                TimeLimiterRegistry.of(builder.timeLimiterConfig),
                RetryRegistry.of(builder.retryConfig),
                TimerRegistry.ofDefaults(meterRegistry),
                meterRegistry);
        return factory.pipeline(NAME);
    }

    private static final class PipelineBuilder {

        private CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .build();
        private RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(10_000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        private BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(100)
                .maxWaitDuration(Duration.ZERO)
                .build();
        private TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))
                .build();
        private RetryConfig retryConfig = RetryConfig.custom().maxAttempts(1).build();

        PipelineBuilder circuitBreaker(CircuitBreakerConfig config) {
            this.circuitBreakerConfig = config;
            return this;
        }

        PipelineBuilder rateLimiter(RateLimiterConfig config) {
            this.rateLimiterConfig = config;
            return this;
        }

        PipelineBuilder bulkhead(BulkheadConfig config) {
            this.bulkheadConfig = config;
            return this;
        }

        PipelineBuilder timeLimiter(TimeLimiterConfig config) {
            this.timeLimiterConfig = config;
            return this;
        }

        PipelineBuilder retry(RetryConfig config) {
            this.retryConfig = config;
            return this;
        }
    }
}
