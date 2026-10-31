package com.clau.resiliencereactiveexample.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProductR2dbcRepository extends R2dbcRepository<ProductRow, UUID> {

    Mono<ProductRow> findBySkuAndActiveIsTrue(String sku);

    Flux<ProductRow> findAllByActiveIsTrue(Pageable pageable);

    Mono<Long> countByActiveIsTrue();

    Mono<Boolean> existsBySku(String sku);
}
