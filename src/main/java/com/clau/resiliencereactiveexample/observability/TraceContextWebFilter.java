package com.clau.resiliencereactiveexample.observability;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        TraceContext traceContext = TraceContext.create(
                headers.getFirst(TraceContext.HEADER_REQUEST_ID),
                headers.getFirst(TraceContext.HEADER_CORRELATION_ID),
                headers.getFirst(TraceContext.HEADER_TRANSACTION_ID),
                headers.getFirst(TraceContext.HEADER_SESSION_ID),
                headers.getFirst(TraceContext.HEADER_ORIGIN_SERVICE),
                exchange.getRequest().getMethod().name() + " " + exchange.getRequest().getPath().value());

        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
            responseHeaders.set(TraceContext.HEADER_REQUEST_ID, traceContext.requestId());
            responseHeaders.set(TraceContext.HEADER_CORRELATION_ID, traceContext.correlationId());
            responseHeaders.set(TraceContext.HEADER_TRANSACTION_ID, traceContext.transactionId());
            return Mono.empty();
        });

        return chain.filter(exchange)
                .contextWrite(context -> TraceContextHolder.write(context, traceContext));
    }
}
