package com.clau.resiliencereactiveexample.infrastructure.http;

import com.clau.resiliencereactiveexample.config.IntegrationProperties;
import com.clau.resiliencereactiveexample.config.WebClientFactory;
import com.clau.resiliencereactiveexample.resilience.ResilienceNames;
import com.clau.resiliencereactiveexample.resilience.ResiliencePipelineFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.TimerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class PricingHttpAdapterTest {

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startServer() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopServer() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Test
    void shouldMapASuccessfulQuote() {
        wireMockServer.stubFor(get(urlPathEqualTo("/pricing/v1/quotes/SKU-OK-0001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sku":"SKU-OK-0001","finalPrice":1299.90,"currency":"BRL","discountPercentage":10.0}
                                """)));

        StepVerifier.create(adapter(defaultRetry(), Duration.ofSeconds(2)).quote("SKU-OK-0001"))
                .assertNext(quote -> {
                    assertThat(quote.finalPrice()).isEqualByComparingTo("1299.90");
                    assertThat(quote.currency()).isEqualTo("BRL");
                    assertThat(quote.degraded()).isFalse();
                    assertThat(quote.source()).isEqualTo(ResilienceNames.PRICING_SERVICE);
                })
                .verifyComplete();
    }

    @Test
    void shouldPropagateTraceHeadersDownstream() {
        wireMockServer.stubFor(get(urlPathEqualTo("/pricing/v1/quotes/SKU-OK-0002"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sku":"SKU-OK-0002","finalPrice":10.00,"currency":"BRL","discountPercentage":0.0}
                                """)));

        adapter(defaultRetry(), Duration.ofSeconds(2)).quote("SKU-OK-0002").block();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/pricing/v1/quotes/SKU-OK-0002"))
                .withHeader("X-Request-Id", WireMock.matching(".+"))
                .withHeader("X-Correlation-Id", WireMock.matching(".+"))
                .withHeader("X-Transaction-Id", WireMock.matching(".+"))
                .withHeader("X-Origin-Service", WireMock.equalTo("resilience-reactive-example")));
    }

    @Test
    void shouldRecoverFromTransientServerErrorsUsingExponentialBackoff() {
        String url = "/pricing/v1/quotes/SKU-FLAKY-0003";
        wireMockServer.stubFor(get(urlPathEqualTo(url)).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wireMockServer.stubFor(get(urlPathEqualTo(url)).inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(get(urlPathEqualTo(url)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sku":"SKU-FLAKY-0003","finalPrice":399.90,"currency":"BRL","discountPercentage":5.0}
                                """)));

        StepVerifier.create(adapter(defaultRetry(), Duration.ofSeconds(5)).quote("SKU-FLAKY-0003"))
                .assertNext(quote -> assertThat(quote.finalPrice()).isEqualByComparingTo("399.90"))
                .verifyComplete();

        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo(url)));
    }

    @Test
    void shouldNotRetryClientErrorsBecauseTheyAreNotTransient() {
        String url = "/pricing/v1/quotes/SKU-GONE-0004";
        wireMockServer.stubFor(get(urlPathEqualTo(url)).willReturn(aResponse().withStatus(404)));

        StepVerifier.create(adapter(defaultRetry(), Duration.ofSeconds(2)).quote("SKU-GONE-0004"))
                .expectError(DownstreamClientException.class)
                .verify();

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(url)));
    }

    @Test
    void shouldCutCallsSlowerThanTheConfiguredTimeLimiter() {
        String url = "/pricing/v1/quotes/SKU-SLOW-0005";
        wireMockServer.stubFor(get(urlPathEqualTo(url))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sku\":\"SKU-SLOW-0005\",\"finalPrice\":1.00,\"currency\":\"BRL\"}")));

        StepVerifier.create(adapter(noRetry(), Duration.ofMillis(250)).quote("SKU-SLOW-0005"))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    private PricingHttpAdapter adapter(RetryConfig retryConfig, Duration timeLimiterTimeout) {
        IntegrationProperties properties = new IntegrationProperties(Map.of(
                ResilienceNames.PRICING_SERVICE, new IntegrationProperties.Endpoint(
                        "http://localhost:" + wireMockServer.port(),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(3),
                        20,
                        Duration.ofMillis(500))));

        WebClientFactory webClientFactory = new WebClientFactory(WebClient.builder(), properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ResiliencePipelineFactory pipelineFactory = new ResiliencePipelineFactory(
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(100)
                        .ignoreExceptions(DownstreamClientException.class)
                        .build()),
                RateLimiterRegistry.of(RateLimiterConfig.custom()
                        .limitForPeriod(1_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()),
                BulkheadRegistry.of(BulkheadConfig.custom()
                        .maxConcurrentCalls(20)
                        .maxWaitDuration(Duration.ZERO)
                        .build()),
                TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                        .timeoutDuration(timeLimiterTimeout)
                        .cancelRunningFuture(true)
                        .build()),
                RetryRegistry.of(retryConfig),
                TimerRegistry.ofDefaults(meterRegistry),
                meterRegistry);

        return new PricingHttpAdapter(webClientFactory, pipelineFactory, Schedulers.parallel());
    }

    private RetryConfig defaultRetry() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(20), 2.0d))
                .ignoreExceptions(DownstreamClientException.class)
                .build();
    }

    private RetryConfig noRetry() {
        return RetryConfig.custom().maxAttempts(1).build();
    }
}
