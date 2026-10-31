package com.clau.resiliencereactiveexample.infrastructure.http;

import com.clau.resiliencereactiveexample.config.SchedulerConfig;
import com.clau.resiliencereactiveexample.config.WebClientFactory;
import com.clau.resiliencereactiveexample.domain.Availability;
import com.clau.resiliencereactiveexample.domain.port.InventoryPort;
import com.clau.resiliencereactiveexample.infrastructure.http.dto.InventoryResponse;
import com.clau.resiliencereactiveexample.resilience.ResilienceNames;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipeline;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipelineFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Slf4j
@Component
public class InventoryHttpAdapter implements InventoryPort {

    private final WebClient webClient;
    private final ResiliencePipeline pipeline;
    private final Scheduler integrationScheduler;

    public InventoryHttpAdapter(WebClientFactory webClientFactory,
                                ResiliencePipelineFactory pipelineFactory,
                                @Qualifier(SchedulerConfig.INTEGRATION_SCHEDULER) Scheduler integrationScheduler) {
        this.webClient = webClientFactory.client(ResilienceNames.INVENTORY_SERVICE);
        this.pipeline = pipelineFactory.pipeline(ResilienceNames.INVENTORY_SERVICE);
        this.integrationScheduler = integrationScheduler;
    }

    @Override
    public Mono<Availability> availability(String sku) {
        Mono<Availability> call = webClient.get()
                .uri("/inventory/v1/availability/{sku}", sku)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new DownstreamClientException(ResilienceNames.INVENTORY_SERVICE, response.statusCode().value(), body)))
                .bodyToMono(InventoryResponse.class)
                .map(response -> toDomain(sku, response));

        return pipeline.protect(call).subscribeOn(integrationScheduler);
    }

    private Availability toDomain(String sku, InventoryResponse response) {
        int quantity = response.quantity() == null ? 0 : response.quantity();
        return new Availability(
                sku,
                quantity,
                response.warehouse() == null ? "unknown" : response.warehouse(),
                quantity > 0,
                ResilienceNames.INVENTORY_SERVICE,
                false);
    }
}
