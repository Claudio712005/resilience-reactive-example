package com.clau.resiliencereactiveexample.infrastructure.cache;

import com.clau.resiliencereactiveexample.config.CacheProperties;
import com.clau.resiliencereactiveexample.infrastructure.chaos.ChaosSwitch;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipelineFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.TimerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilienceCacheTest {

    private static final String REGION = "product-view";
    private static final String KEY = "SKU-NOTE-0001";
    private static final String REMOTE_KEY = "catalog:product-view:SKU-NOTE-0001";

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOperations;
    private ChaosSwitch chaosSwitch;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        chaosSwitch = new ChaosSwitch();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void shouldReadFromRedisAndPromoteTheEntryToTheLocalTier() {
        when(valueOperations.get(REMOTE_KEY)).thenReturn(Mono.just("{\"value\":\"cached\"}"));
        ResilienceCache cache = cache(properties(true, true));

        StepVerifier.create(cache.find(REGION, KEY, Payload.class))
                .assertNext(payload -> assertThat(payload.value()).isEqualTo("cached"))
                .verifyComplete();

        StepVerifier.create(cache.find(REGION, KEY, Payload.class))
                .assertNext(payload -> assertThat(payload.value()).isEqualTo("cached"))
                .verifyComplete();

        verify(valueOperations, times(1)).get(REMOTE_KEY);
        assertThat(counter("hit", "remote")).isEqualTo(1.0d);
        assertThat(counter("hit", "local")).isEqualTo(1.0d);
    }

    @Test
    void shouldDegradeToEmptyWhenRedisFails() {
        when(valueOperations.get(REMOTE_KEY)).thenReturn(Mono.error(new IllegalStateException("redis down")));
        ResilienceCache cache = cache(properties(true, true));

        StepVerifier.create(cache.find(REGION, KEY, Payload.class)).verifyComplete();

        assertThat(counter("error", "remote")).isEqualTo(1.0d);
    }

    @Test
    void shouldDegradeToEmptyWhenRedisIsMarkedUnavailable() {
        chaosSwitch.setRedisUnavailable(true);
        ResilienceCache cache = cache(properties(true, true));

        StepVerifier.create(cache.find(REGION, KEY, Payload.class)).verifyComplete();

        verify(valueOperations, never()).get(anyString());
        assertThat(counter("error", "remote")).isEqualTo(1.0d);
    }

    @Test
    void shouldReportAMissWhenRedisHasNoEntry() {
        when(valueOperations.get(REMOTE_KEY)).thenReturn(Mono.empty());
        ResilienceCache cache = cache(properties(true, true));

        StepVerifier.create(cache.find(REGION, KEY, Payload.class)).verifyComplete();

        assertThat(counter("miss", "remote")).isEqualTo(1.0d);
        assertThat(counter("miss", "local")).isEqualTo(1.0d);
    }

    @Test
    void shouldWriteThroughToRedisWithTheConfiguredTtl() {
        when(valueOperations.set(eq(REMOTE_KEY), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        ResilienceCache cache = cache(properties(true, true));

        StepVerifier.create(cache.put(REGION, KEY, new Payload("fresh"))).verifyComplete();

        verify(valueOperations).set(eq(REMOTE_KEY), eq("{\"value\":\"fresh\"}"), eq(Duration.ofMinutes(5)));
    }

    @Test
    void shouldSkipEveryTierWhenTheCacheIsDisabled() {
        ResilienceCache cache = cache(properties(false, true));

        StepVerifier.create(cache.find(REGION, KEY, Payload.class)).verifyComplete();

        verify(valueOperations, never()).get(anyString());
    }

    private double counter(String kind, String tier) {
        var counter = meterRegistry.find("resilience4j.cache.calls")
                .tag("kind", kind)
                .tag("tier", tier)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private CacheProperties properties(boolean enabled, boolean localEnabled) {
        return new CacheProperties(
                enabled,
                Duration.ofMinutes(5),
                Duration.ofMillis(150),
                Duration.ofMillis(200),
                localEnabled,
                Duration.ofSeconds(15),
                1_000,
                "catalog");
    }

    private ResilienceCache cache(CacheProperties properties) {
        ResiliencePipelineFactory pipelineFactory = new ResiliencePipelineFactory(
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(100)
                        .build()),
                RateLimiterRegistry.of(RateLimiterConfig.custom()
                        .limitForPeriod(10_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()),
                BulkheadRegistry.of(BulkheadConfig.custom()
                        .maxConcurrentCalls(100)
                        .maxWaitDuration(Duration.ZERO)
                        .build()),
                TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(2))
                        .build()),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                TimerRegistry.ofDefaults(meterRegistry),
                meterRegistry);

        return new ResilienceCache(redisTemplate, new ObjectMapper(), properties,
                chaosSwitch, meterRegistry, pipelineFactory);
    }

    private record Payload(String value) {
    }
}
