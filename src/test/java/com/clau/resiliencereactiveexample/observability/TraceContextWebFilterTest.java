package com.clau.resiliencereactiveexample.observability;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextWebFilterTest {

    private final TraceContextWebFilter filter = new TraceContextWebFilter();

    @Test
    void shouldReuseInboundIdentifiersAndExposeThemThroughTheReactorContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products/SKU-NOTE-0001")
                        .header(TraceContext.HEADER_REQUEST_ID, "req-123")
                        .header(TraceContext.HEADER_CORRELATION_ID, "corr-456")
                        .header(TraceContext.HEADER_TRANSACTION_ID, "txn-789")
                        .header(TraceContext.HEADER_ORIGIN_SERVICE, "checkout-bff"));

        AtomicReference<TraceContext> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, captureChainContext(captured))).verifyComplete();

        TraceContext traceContext = captured.get();
        assertThat(traceContext.requestId()).isEqualTo("req-123");
        assertThat(traceContext.correlationId()).isEqualTo("corr-456");
        assertThat(traceContext.transactionId()).isEqualTo("txn-789");
        assertThat(traceContext.originService()).isEqualTo("checkout-bff");
        assertThat(traceContext.sessionId()).isEqualTo("anonymous");
        assertThat(traceContext.operation()).isEqualTo("GET /api/v1/products/SKU-NOTE-0001");
        assertThat(traceContext.asMdc())
                .containsKeys(TraceContext.MDC_REQUEST_ID, TraceContext.MDC_CORRELATION_ID,
                        TraceContext.MDC_TRANSACTION_ID, TraceContext.MDC_OPERATION);
    }

    @Test
    void shouldGenerateMissingIdentifiersAndEchoThemBackToTheCaller() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products"));

        AtomicReference<TraceContext> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, captureChainContext(captured))).verifyComplete();

        TraceContext traceContext = captured.get();
        assertThat(traceContext.requestId()).isNotBlank();
        assertThat(traceContext.correlationId()).isNotBlank();
        assertThat(traceContext.transactionId()).isNotBlank();
        assertThat(traceContext.requestId()).isNotEqualTo(traceContext.correlationId());

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertThat(responseHeaders.getFirst(TraceContext.HEADER_REQUEST_ID)).isEqualTo(traceContext.requestId());
        assertThat(responseHeaders.getFirst(TraceContext.HEADER_CORRELATION_ID)).isEqualTo(traceContext.correlationId());
        assertThat(responseHeaders.getFirst(TraceContext.HEADER_TRANSACTION_ID)).isEqualTo(traceContext.transactionId());
    }

    private org.springframework.web.server.WebFilterChain captureChainContext(AtomicReference<TraceContext> captured) {
        return exchange -> TraceContextHolder.current()
                .doOnNext(captured::set)
                .then(Mono.defer(() -> exchange.getResponse().setComplete()));
    }
}
