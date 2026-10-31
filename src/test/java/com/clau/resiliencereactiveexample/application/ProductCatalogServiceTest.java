package com.clau.resiliencereactiveexample.application;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCatalogServiceTest {

    private static final String SKU = "SKU-NOTE-0001";

    private final Product product = Product.newProduct(SKU, "Notebook Pro 14", "14 polegadas",
            "informatica", "Nexora", new BigDecimal("8999.90"));

    private FakeRepository repository;
    private FakeCache cache;
    private FakePricing pricing;
    private FakeInventory inventory;
    private FakeRating rating;
    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository(product);
        cache = new FakeCache();
        pricing = new FakePricing();
        inventory = new FakeInventory();
        rating = new FakeRating();
        service = new ProductCatalogService(repository, cache, pricing, inventory, rating,
                new SimpleMeterRegistry(), Schedulers.immediate());
    }

    @Test
    void shouldComposeTheViewFromOriginAndWarmTheCacheWhenEverySourceIsHealthy() {
        StepVerifier.create(service.findViewBySku(SKU))
                .assertNext(view -> {
                    assertThat(view.serving().servedFrom()).isEqualTo("database-and-downstreams");
                    assertThat(view.serving().cacheHit()).isFalse();
                    assertThat(view.serving().fullyHealthy()).isTrue();
                    assertThat(view.price().finalPrice()).isEqualByComparingTo("1000.00");
                    assertThat(view.availability().quantity()).isEqualTo(10);
                    assertThat(view.rating().reviewCount()).isEqualTo(5);
                })
                .verifyComplete();

        assertThat(cache.stored).containsKey(SKU);
        assertThat(repository.reads).hasValue(1);
    }

    @Test
    void shouldServeFromCacheWithoutTouchingTheOrigin() {
        service.findViewBySku(SKU).block();
        repository.reads.set(0);
        pricing.calls.set(0);

        StepVerifier.create(service.findViewBySku(SKU))
                .assertNext(view -> {
                    assertThat(view.serving().cacheHit()).isTrue();
                    assertThat(view.serving().servedFrom()).isEqualTo("redis-cache");
                })
                .verifyComplete();

        assertThat(repository.reads).hasValue(0);
        assertThat(pricing.calls).hasValue(0);
    }

    @Test
    void shouldDegradePricingWithTheCatalogBasePriceAndSkipTheCacheWrite() {
        pricing.failure = new IOException("pricing engine down");

        StepVerifier.create(service.findViewBySku(SKU))
                .assertNext(view -> {
                    assertThat(view.serving().degradedSources()).containsExactly("pricing");
                    assertThat(view.serving().fullyHealthy()).isFalse();
                    assertThat(view.price().source()).isEqualTo("catalog-base-price");
                    assertThat(view.price().finalPrice()).isEqualByComparingTo("8999.90");
                })
                .verifyComplete();

        assertThat(cache.stored).isEmpty();
    }

    @Test
    void shouldStillAnswerWhenEveryDownstreamIsDown() {
        pricing.failure = new IOException("pricing down");
        inventory.failure = new IOException("inventory down");
        rating.failure = new IOException("rating down");

        StepVerifier.create(service.findViewBySku(SKU))
                .assertNext(view -> assertThat(view.serving().degradedSources())
                        .containsExactly("pricing", "inventory", "rating"))
                .verifyComplete();
    }

    @Test
    void shouldFailWithNotFoundWhenTheProductDoesNotExist() {
        StepVerifier.create(service.findViewBySku("SKU-DOES-NOT-EXIST"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void shouldTranslateSourceOfTruthFailuresIntoCatalogUnavailable() {
        repository.failure = new IllegalStateException("connection pool exhausted");

        StepVerifier.create(service.findViewBySku(SKU))
                .expectError(CatalogUnavailableException.class)
                .verify();
    }

    @Test
    void shouldServeTheCatalogEvenWhenTheCacheLayerIsBroken() {
        cache.broken = true;

        StepVerifier.create(service.findViewBySku(SKU))
                .assertNext(view -> assertThat(view.serving().servedFrom()).isEqualTo("database-and-downstreams"))
                .verifyComplete();
    }

    @Test
    void shouldSkipMissingProductsWhenResolvingSeveralSkusInParallel() {
        StepVerifier.create(service.findViews(java.util.List.of(SKU, "SKU-UNKNOWN-0000")))
                .assertNext(view -> assertThat(view.product().sku()).isEqualTo(SKU))
                .verifyComplete();
    }

    private static final class FakeRepository implements ProductRepositoryPort {

        private final Product product;
        private final AtomicInteger reads = new AtomicInteger();
        private Throwable failure;

        private FakeRepository(Product product) {
            this.product = product;
        }

        @Override
        public Mono<Product> findBySku(String sku) {
            return Mono.defer(() -> {
                reads.incrementAndGet();
                if (failure != null) {
                    return Mono.error(failure);
                }
                return product.sku().equals(sku) ? Mono.just(product) : Mono.empty();
            });
        }

        @Override
        public Flux<Product> findPage(int page, int size) {
            return Flux.just(product);
        }

        @Override
        public Mono<Long> countActive() {
            return Mono.just(1L);
        }

        @Override
        public Mono<Product> create(Product toCreate) {
            return Mono.just(toCreate);
        }
    }

    private static final class FakeCache implements ProductViewCachePort {

        private final Map<String, ProductView> stored = new ConcurrentHashMap<>();
        private boolean broken;

        @Override
        public Mono<ProductView> find(String sku) {
            return broken ? Mono.empty() : Mono.justOrEmpty(stored.get(sku));
        }

        @Override
        public Mono<Void> put(String sku, ProductView view) {
            if (!broken) {
                stored.put(sku, view);
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> evict(String sku) {
            stored.remove(sku);
            return Mono.empty();
        }
    }

    private static final class FakePricing implements PricingPort {

        private final AtomicInteger calls = new AtomicInteger();
        private Throwable failure;

        @Override
        public Mono<PriceQuote> quote(String sku) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                return failure != null
                        ? Mono.error(failure)
                        : Mono.just(new PriceQuote(sku, new BigDecimal("1000.00"), "BRL",
                        BigDecimal.TEN, "pricing-service", false));
            });
        }
    }

    private static final class FakeInventory implements InventoryPort {

        private Throwable failure;

        @Override
        public Mono<Availability> availability(String sku) {
            return Mono.defer(() -> failure != null
                    ? Mono.error(failure)
                    : Mono.just(new Availability(sku, 10, "SP-01", true, "inventory-service", false)));
        }
    }

    private static final class FakeRating implements RatingPort {

        private Throwable failure;

        @Override
        public Mono<Rating> rating(String sku) {
            return Mono.defer(() -> failure != null
                    ? Mono.error(failure)
                    : Mono.just(new Rating(sku, 4.5d, 5, "rating-service", false)));
        }
    }
}
