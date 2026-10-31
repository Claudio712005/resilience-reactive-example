package com.clau.resiliencereactiveexample.domain.port;

import com.clau.resiliencereactiveexample.domain.ProductView;
import reactor.core.publisher.Mono;

public interface ProductViewCachePort {

    Mono<ProductView> find(String sku);

    Mono<Void> put(String sku, ProductView view);

    Mono<Void> evict(String sku);
}
