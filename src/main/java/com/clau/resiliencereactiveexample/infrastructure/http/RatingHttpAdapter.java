package com.clau.resiliencereactiveexample.infrastructure.http;

import com.clau.resiliencereactiveexample.config.SchedulerConfig;
import com.clau.resiliencereactiveexample.config.WebClientFactory;
import com.clau.resiliencereactiveexample.domain.Rating;
import com.clau.resiliencereactiveexample.domain.port.RatingPort;
import com.clau.resiliencereactiveexample.infrastructure.http.dto.RatingResponse;
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
public class RatingHttpAdapter implements RatingPort {

    private final WebClient webClient;
    private final ResiliencePipeline pipeline;
    private final Scheduler integrationScheduler;

    public RatingHttpAdapter(WebClientFactory webClientFactory,
                             ResiliencePipelineFactory pipelineFactory,
                             @Qualifier(SchedulerConfig.INTEGRATION_SCHEDULER) Scheduler integrationScheduler) {
        this.webClient = webClientFactory.client(ResilienceNames.RATING_SERVICE);
        this.pipeline = pipelineFactory.pipeline(ResilienceNames.RATING_SERVICE);
        this.integrationScheduler = integrationScheduler;
    }

    @Override
    public Mono<Rating> rating(String sku) {
        Mono<Rating> call = webClient.get()
                .uri("/ratings/v1/products/{sku}", sku)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new DownstreamClientException(ResilienceNames.RATING_SERVICE, response.statusCode().value(), body)))
                .bodyToMono(RatingResponse.class)
                .map(response -> toDomain(sku, response));

        return pipeline.protect(call).subscribeOn(integrationScheduler);
    }

    private Rating toDomain(String sku, RatingResponse response) {
        return new Rating(
                sku,
                response.average() == null ? 0.0d : response.average(),
                response.reviewCount() == null ? 0 : response.reviewCount(),
                ResilienceNames.RATING_SERVICE,
                false);
    }
}
