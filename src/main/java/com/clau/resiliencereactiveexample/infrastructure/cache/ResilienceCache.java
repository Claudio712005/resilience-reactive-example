package com.clau.resiliencereactiveexample.infrastructure.cache;

import com.clau.resiliencereactiveexample.config.CacheProperties;
import com.clau.resiliencereactiveexample.infrastructure.chaos.ChaosSwitch;
import com.clau.resiliencereactiveexample.resilience.ResilienceNames;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipeline;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipelineFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

@Slf4j
@Component
public class ResilienceCache {

    private static final String METRIC_CALLS = "resilience4j.cache.calls";
    private static final String TIER_LOCAL = "local";
    private static final String TIER_REMOTE = "remote";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;
    private final ChaosSwitch chaosSwitch;
    private final MeterRegistry meterRegistry;
    private final ResiliencePipeline pipeline;
    private final Cache<String, String> localCache;

    public ResilienceCache(ReactiveStringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           CacheProperties properties,
                           ChaosSwitch chaosSwitch,
                           MeterRegistry meterRegistry,
                           ResiliencePipelineFactory pipelineFactory) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chaosSwitch = chaosSwitch;
        this.meterRegistry = meterRegistry;
        this.pipeline = pipelineFactory.pipeline(ResilienceNames.PRODUCT_CACHE);
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.localMaxSize())
                .expireAfterWrite(properties.localTtl())
                .recordStats()
                .build();
    }

    public <T> Mono<T> find(String region, String key, Class<T> type) {
        if (!properties.enabled()) {
            return Mono.empty();
        }

        String namespacedKey = region + ":" + key;
        return localLookup(region, namespacedKey, type)
                .switchIfEmpty(Mono.defer(() -> remoteLookup(region, namespacedKey, type)));
    }

    public <T> Mono<Void> put(String region, String key, T value) {
        if (!properties.enabled() || value == null) {
            return Mono.empty();
        }

        String namespacedKey = region + ":" + key;
        return serialize(value)
                .flatMap(payload -> {
                    if (properties.localEnabled()) {
                        localCache.put(namespacedKey, payload);
                    }
                    return guardedRedis(
                            () -> redisTemplate.opsForValue()
                                    .set(properties.remoteKey(namespacedKey), payload, properties.remoteTtl())
                                    .timeout(properties.writeTimeout()),
                            region,
                            "write");
                })
                .doOnNext(stored -> log.debug("cache stored region={} key={} remoteAck={}", region, key, stored))
                .then();
    }

    public Mono<Void> evict(String region, String key) {
        String namespacedKey = region + ":" + key;
        localCache.invalidate(namespacedKey);
        return guardedRedis(
                () -> redisTemplate.delete(properties.remoteKey(namespacedKey)).timeout(properties.writeTimeout()),
                region,
                "evict")
                .then();
    }

    private <T> Mono<T> localLookup(String region, String namespacedKey, Class<T> type) {
        if (!properties.localEnabled()) {
            return Mono.empty();
        }
        String cached = localCache.getIfPresent(namespacedKey);
        if (cached == null) {
            count(region, "miss", TIER_LOCAL);
            return Mono.empty();
        }
        count(region, "hit", TIER_LOCAL);
        return deserialize(cached, type, region);
    }

    private <T> Mono<T> remoteLookup(String region, String namespacedKey, Class<T> type) {
        Supplier<Mono<String>> lookup = () -> redisTemplate.opsForValue()
                .get(properties.remoteKey(namespacedKey))
                .timeout(properties.remoteTimeout());

        return guardedRedis(lookup, region, "read")
                .flatMap(payload -> {
                    count(region, "hit", TIER_REMOTE);
                    if (properties.localEnabled()) {
                        localCache.put(namespacedKey, payload);
                    }
                    return deserialize(payload, type, region);
                })
                .switchIfEmpty(Mono.fromRunnable(() -> count(region, "miss", TIER_REMOTE)));
    }

    private <T> Mono<T> guardedRedis(Supplier<Mono<T>> operation, String region, String action) {
        Mono<T> withChaos = Mono.defer(() -> chaosSwitch.isRedisUnavailable()
                ? Mono.error(new CacheUnavailableException("redis marked unavailable by chaos switch"))
                : operation.get());

        return pipeline.protect(withChaos)
                .onErrorResume(error -> {
                    count(region, "error", TIER_REMOTE);
                    log.warn("cache degraded to no-op region={} action={} error={} message={}",
                            region, action, error.getClass().getSimpleName(), error.getMessage());
                    return Mono.empty();
                });
    }

    private <T> Mono<String> serialize(T value) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(value))
                .onErrorResume(error -> {
                    log.warn("cache serialization failed type={} error={}",
                            value.getClass().getSimpleName(), error.getMessage());
                    return Mono.empty();
                });
    }

    private <T> Mono<T> deserialize(String payload, Class<T> type, String region) {
        return Mono.fromCallable(() -> objectMapper.readValue(payload, type))
                .onErrorResume(error -> {
                    count(region, "error", TIER_LOCAL);
                    log.warn("cache deserialization failed region={} type={} error={}",
                            region, type.getSimpleName(), error.getMessage());
                    return Mono.empty();
                });
    }

    private void count(String region, String kind, String tier) {
        Counter.builder(METRIC_CALLS)
                .description("Resilience cache calls grouped by outcome and tier")
                .tag("name", region)
                .tag("kind", kind)
                .tag("tier", tier)
                .register(meterRegistry)
                .increment();
    }
}
