package com.clau.resiliencereactiveexample.infrastructure.persistence;

import com.clau.resiliencereactiveexample.config.DataAccessProperties;
import com.clau.resiliencereactiveexample.config.SchedulerConfig;
import com.clau.resiliencereactiveexample.domain.Product;
import com.clau.resiliencereactiveexample.domain.port.ProductRepositoryPort;
import com.clau.resiliencereactiveexample.infrastructure.chaos.ChaosSwitch;
import com.clau.resiliencereactiveexample.resilience.ResilienceNames;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipeline;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipelineFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;

@Slf4j
@Repository
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final ProductR2dbcRepository repository;
    private final R2dbcEntityTemplate template;
    private final DataAccessProperties properties;
    private final ChaosSwitch chaosSwitch;
    private final Scheduler persistenceScheduler;
    private final ResiliencePipeline readPipeline;
    private final ResiliencePipeline writePipeline;

    public ProductRepositoryAdapter(ProductR2dbcRepository repository,
                                    R2dbcEntityTemplate template,
                                    DataAccessProperties properties,
                                    ChaosSwitch chaosSwitch,
                                    @Qualifier(SchedulerConfig.PERSISTENCE_SCHEDULER) Scheduler persistenceScheduler,
                                    ResiliencePipelineFactory pipelineFactory) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
        this.chaosSwitch = chaosSwitch;
        this.persistenceScheduler = persistenceScheduler;
        this.readPipeline = pipelineFactory.pipeline(ResilienceNames.PRODUCT_DATABASE_READ);
        this.writePipeline = pipelineFactory.pipeline(ResilienceNames.PRODUCT_DATABASE_WRITE);
    }

    @Override
    public Mono<Product> findBySku(String sku) {
        Mono<Product> query = injectedChaos()
                .then(repository.findBySkuAndActiveIsTrue(sku))
                .map(ProductRow::toDomain)
                .timeout(properties.readTimeout())
                .doOnSubscribe(subscription -> log.debug("database read started sku={} thread={}", sku, Thread.currentThread().getName()))
                .doOnNext(product -> log.debug("database read completed sku={} id={}", sku, product.id()));

        return readPipeline.protect(query).subscribeOn(persistenceScheduler);
    }

    @Override
    public Flux<Product> findPage(int page, int size) {
        int effectiveSize = Math.min(size <= 0 ? 20 : size, properties.maxPageSize());
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), effectiveSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Flux<Product> query = injectedChaos()
                .thenMany(repository.findAllByActiveIsTrue(pageRequest))
                .map(ProductRow::toDomain)
                .timeout(properties.listTimeout());

        return readPipeline.protect(query).subscribeOn(persistenceScheduler);
    }

    @Override
    public Mono<Long> countActive() {
        return readPipeline.protect(repository.countByActiveIsTrue().timeout(properties.readTimeout()))
                .subscribeOn(persistenceScheduler);
    }

    @Override
    public Mono<Product> create(Product product) {
        Mono<Product> insert = repository.existsBySku(product.sku())
                .flatMap(exists -> exists
                        ? Mono.error(new DuplicatedSkuException(product.sku()))
                        : template.insert(ProductRow.fromDomain(product)).map(ProductRow::toDomain))
                .timeout(properties.writeTimeout())
                .doOnNext(created -> log.info("product persisted sku={} id={}", created.sku(), created.id()));

        return writePipeline.protect(insert).subscribeOn(persistenceScheduler);
    }

    private Mono<Void> injectedChaos() {
        return Mono.defer(() -> {
            if (chaosSwitch.isDatabaseUnavailable()) {
                return Mono.error(new DataStoreUnavailableException("database marked unavailable by chaos switch"));
            }
            long latency = chaosSwitch.databaseLatencyMillis();
            return latency == 0
                    ? Mono.empty()
                    : Mono.delay(Duration.ofMillis(latency)).then();
        });
    }
}
