package com.clau.resiliencereactiveexample.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
public class TracePropagationExchangeFilter implements ExchangeFilterFunction {

    private final String serviceName;

    public TracePropagationExchangeFilter(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return TraceContextHolder.current().flatMap(traceContext -> {
            ClientRequest propagated = ClientRequest.from(request)
                    .header(TraceContext.HEADER_REQUEST_ID, traceContext.requestId())
                    .header(TraceContext.HEADER_CORRELATION_ID, traceContext.correlationId())
                    .header(TraceContext.HEADER_TRANSACTION_ID, traceContext.transactionId())
                    .header(TraceContext.HEADER_SESSION_ID, traceContext.sessionId())
                    .header(TraceContext.HEADER_ORIGIN_SERVICE, "resilience-reactive-example")
                    .build();

            long startedAt = System.nanoTime();
            log.info("downstream call started service={} method={} uri={}",
                    serviceName, propagated.method(), propagated.url());

            return next.exchange(propagated)
                    .doOnNext(response -> log.info("downstream call finished service={} status={} elapsedMs={}",
                            serviceName, response.statusCode().value(), elapsedMs(startedAt)))
                    .doOnError(error -> log.warn("downstream call failed service={} error={} message={} elapsedMs={}",
                            serviceName, error.getClass().getSimpleName(), error.getMessage(), elapsedMs(startedAt)));
        });
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
