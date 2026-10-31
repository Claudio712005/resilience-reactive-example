package com.clau.resiliencereactiveexample.domain.port;

import com.clau.resiliencereactiveexample.domain.Product;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductRepositoryPort {

    Mono<Product> findBySku(String sku);

    Flux<Product> findPage(int page, int size);

    Mono<Long> countActive();

    Mono<Product> create(Product product);
}
