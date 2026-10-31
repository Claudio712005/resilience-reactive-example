package com.clau.resiliencereactiveexample.infrastructure.cache;

import com.clau.resiliencereactiveexample.domain.ProductView;
import com.clau.resiliencereactiveexample.domain.port.ProductViewCachePort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisProductViewCacheAdapter implements ProductViewCachePort {

    private static final String REGION = "product-view";

    private final ResilienceCache cache;

    public RedisProductViewCacheAdapter(ResilienceCache cache) {
        this.cache = cache;
    }

    @Override
    public Mono<ProductView> find(String sku) {
        return cache.find(REGION, sku, ProductView.class);
    }

    @Override
    public Mono<Void> put(String sku, ProductView view) {
        return cache.put(REGION, sku, view);
    }

    @Override
    public Mono<Void> evict(String sku) {
        return cache.evict(REGION, sku);
    }
}
