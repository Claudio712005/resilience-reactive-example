package com.clau.resiliencereactiveexample.infrastructure.http;

import com.clau.resiliencereactiveexample.config.SchedulerConfig;
import com.clau.resiliencereactiveexample.config.WebClientFactory;
import com.clau.resiliencereactiveexample.domain.PriceQuote;
import com.clau.resiliencereactiveexample.domain.port.PricingPort;
import com.clau.resiliencereactiveexample.infrastructure.http.dto.PricingResponse;
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

import java.math.BigDecimal;

@Slf4j
@Component
public class PricingHttpAdapter implements PricingPort {

    private final WebClient webClient;
    private final ResiliencePipeline pipeline;
    private final Scheduler integrationScheduler;

    public PricingHttpAdapter(WebClientFactory webClientFactory,
                              ResiliencePipelineFactory pipelineFactory,
                              @Qualifier(SchedulerConfig.INTEGRATION_SCHEDULER) Scheduler integrationScheduler) {
        this.webClient = webClientFactory.client(ResilienceNames.PRICING_SERVICE);
        this.pipeline = pipelineFactory.pipeline(ResilienceNames.PRICING_SERVICE);
        this.integrationScheduler = integrationScheduler;
    }

    @Override
    public Mono<PriceQuote> quote(String sku) {
        Mono<PriceQuote> call = webClient.get()
                .uri("/pricing/v1/quotes/{sku}", sku)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new DownstreamClientException(ResilienceNames.PRICING_SERVICE, response.statusCode().value(), body)))
                .bodyToMono(PricingResponse.class)
                .map(response -> toDomain(sku, response));

        return pipeline.protect(call).subscribeOn(integrationScheduler);
    }

    private PriceQuote toDomain(String sku, PricingResponse response) {
        return new PriceQuote(
                sku,
                response.finalPrice() == null ? BigDecimal.ZERO : response.finalPrice(),
                response.currency() == null ? "BRL" : response.currency(),
                response.discountPercentage() == null ? BigDecimal.ZERO : response.discountPercentage(),
                ResilienceNames.PRICING_SERVICE,
                false);
    }
}
