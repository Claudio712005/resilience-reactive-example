package com.clau.resiliencereactiveexample.application;

import com.clau.resiliencereactiveexample.config.SchedulerConfig;
import com.clau.resiliencereactiveexample.domain.Availability;
import com.clau.resiliencereactiveexample.domain.PriceQuote;
import com.clau.resiliencereactiveexample.domain.Product;
import com.clau.resiliencereactiveexample.domain.ProductView;
import com.clau.resiliencereactiveexample.domain.Rating;
import com.clau.resiliencereactiveexample.domain.port.InventoryPort;
import com.clau.resiliencereactiveexample.domain.port.PricingPort;
import com.clau.resiliencereactiveexample.domain.port.ProductRepositoryPort;
import com.clau.resiliencereactiveexample.domain.port.ProductViewCachePort;
import com.clau.resiliencereactiveexample.domain.port.RatingPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class ProductCatalogService {

    private static final String SERVED_FROM_CACHE = "redis-cache";
    private static final String SERVED_FROM_ORIGIN = "database-and-downstreams";

    private final ProductRepositoryPort repository;
    private final ProductViewCachePort cache;
    private final PricingPort pricing;
    private final InventoryPort inventory;
    private final RatingPort rating;
    private final MeterRegistry meterRegistry;
    private final Scheduler enrichmentScheduler;

    public ProductCatalogService(ProductRepositoryPort repository,
                                 ProductViewCachePort cache,
                                 PricingPort pricing,
                                 InventoryPort inventory,
                                 RatingPort rating,
                                 MeterRegistry meterRegistry,
                                 @Qualifier(SchedulerConfig.ENRICHMENT_SCHEDULER) Scheduler enrichmentScheduler) {
        this.repository = repository;
        this.cache = cache;
        this.pricing = pricing;
        this.inventory = inventory;
        this.rating = rating;
        this.meterRegistry = meterRegistry;
        this.enrichmentScheduler = enrichmentScheduler;
    }

    public Mono<ProductView> findViewBySku(String sku) {
        long startedAt = System.nanoTime();

        return cache.find(sku)
                .map(cached -> cached.servedFrom(SERVED_FROM_CACHE, true))
                .doOnNext(cached -> log.info("product view served from cache sku={} degraded={}",
                        sku, cached.serving().degradedSources()))
                .switchIfEmpty(Mono.defer(() -> loadFromOrigin(sku)))
                .doOnSuccess(view -> recordOutcome(view, startedAt, "success"))
                .doOnError(error -> recordFailure(startedAt, error));
    }

    public Flux<ProductView> findViews(List<String> skus) {
        return Flux.fromIterable(skus)
                .distinct()
                .parallel()
                .runOn(enrichmentScheduler)
                .flatMap(sku -> findViewBySku(sku).onErrorResume(ProductNotFoundException.class, error -> Mono.empty()))
                .sequential();
    }

    public Flux<Product> findPage(int page, int size) {
        return repository.findPage(page, size)
                .onErrorMap(error -> new CatalogUnavailableException("Product listing is unavailable", error));
    }

    public Mono<Long> countActive() {
        return repository.countActive();
    }

    public Mono<Product> create(Product product) {
        return repository.create(product)
                .flatMap(created -> cache.evict(created.sku()).thenReturn(created));
    }

    public Mono<Void> evict(String sku) {
        return cache.evict(sku);
    }

    private Mono<ProductView> loadFromOrigin(String sku) {
        return repository.findBySku(sku)
                .switchIfEmpty(Mono.error(new ProductNotFoundException(sku)))
                .onErrorMap(error -> !(error instanceof ProductNotFoundException),
                        error -> new CatalogUnavailableException("Product source of truth is unavailable for sku " + sku, error))
                .flatMap(this::enrich)
                .flatMap(this::cacheWhenHealthy);
    }

    private Mono<ProductView> cacheWhenHealthy(ProductView view) {
        if (!view.serving().fullyHealthy()) {
            log.info("skipping cache write for degraded view sku={} degradedSources={}",
                    view.product().sku(), view.serving().degradedSources());
            return Mono.just(view);
        }
        return cache.put(view.product().sku(), view).thenReturn(view);
    }

    private Mono<ProductView> enrich(Product product) {
        String sku = product.sku();

        Mono<PriceQuote> priceMono = pricing.quote(sku)
                .onErrorResume(error -> {
                    log.warn("pricing degraded sku={} reason={}", sku, error.getClass().getSimpleName());
                    return Mono.just(PriceQuote.fromCatalogBasePrice(sku, product.basePrice()));
                })
                .defaultIfEmpty(PriceQuote.fromCatalogBasePrice(sku, product.basePrice()));

        Mono<Availability> availabilityMono = inventory.availability(sku)
                .onErrorResume(error -> {
                    log.warn("inventory degraded sku={} reason={}", sku, error.getClass().getSimpleName());
                    return Mono.just(Availability.unknown(sku));
                })
                .defaultIfEmpty(Availability.unknown(sku));

        Mono<Rating> ratingMono = rating.rating(sku)
                .onErrorResume(error -> {
                    log.warn("rating degraded sku={} reason={}", sku, error.getClass().getSimpleName());
                    return Mono.just(Rating.unrated(sku));
                })
                .defaultIfEmpty(Rating.unrated(sku));

        return Mono.zip(priceMono, availabilityMono, ratingMono)
                .publishOn(enrichmentScheduler)
                .map(tuple -> ProductView.compose(product, tuple.getT1(), tuple.getT2(), tuple.getT3(),
                        SERVED_FROM_ORIGIN, false))
                .doOnNext(view -> log.info("product view composed sku={} degraded={} thread={}",
                        sku, view.serving().degradedSources(), Thread.currentThread().getName()));
    }

    private void recordOutcome(ProductView view, long startedAt, String outcome) {
        if (view == null) {
            return;
        }
        Timer.builder("catalog.product.view")
                .description("Latency of a fully composed product view")
                .tag("outcome", outcome)
                .tag("served_from", view.serving().servedFrom())
                .tag("cache_hit", String.valueOf(view.serving().cacheHit()))
                .tag("degraded", String.valueOf(!view.serving().fullyHealthy()))
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private void recordFailure(long startedAt, Throwable error) {
        Timer.builder("catalog.product.view")
                .description("Latency of a fully composed product view")
                .tag("outcome", "error")
                .tag("served_from", "none")
                .tag("cache_hit", "false")
                .tag("degraded", "true")
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
        log.error("product view failed error={} message={}", error.getClass().getSimpleName(), error.getMessage());
    }
}
