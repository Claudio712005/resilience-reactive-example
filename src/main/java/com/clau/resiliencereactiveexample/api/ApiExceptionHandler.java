package com.clau.resiliencereactiveexample.api;

import com.clau.resiliencereactiveexample.api.dto.ApiError;
import com.clau.resiliencereactiveexample.application.CatalogUnavailableException;
import com.clau.resiliencereactiveexample.application.ProductNotFoundException;
import com.clau.resiliencereactiveexample.infrastructure.persistence.DuplicatedSkuException;
import com.clau.resiliencereactiveexample.observability.TraceContextHolder;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> handleNotFound(ProductNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicatedSkuException.class)
    public Mono<ResponseEntity<ApiError>> handleDuplicated(DuplicatedSkuException exception) {
        return build(HttpStatus.CONFLICT, "DUPLICATED_SKU", exception.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiError>> handleValidation(WebExchangeBindException exception) {
        String details = exception.getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((left, right) -> left + "; " + right)
                .orElse("invalid payload");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public Mono<ResponseEntity<ApiError>> handleRateLimited(RequestNotPermitted exception) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", exception.getMessage());
    }

    @ExceptionHandler(BulkheadFullException.class)
    public Mono<ResponseEntity<ApiError>> handleBulkheadFull(BulkheadFullException exception) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "BULKHEAD_FULL", exception.getMessage());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public Mono<ResponseEntity<ApiError>> handleOpenCircuit(CallNotPermittedException exception) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN", exception.getMessage());
    }

    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<ApiError>> handleTimeout(TimeoutException exception) {
        return build(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", exception.getMessage());
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    public Mono<ResponseEntity<ApiError>> handleCatalogUnavailable(CatalogUnavailableException exception) {
        log.error("catalog unavailable message={} cause={}", exception.getMessage(),
                exception.getCause() == null ? "none" : exception.getCause().getClass().getSimpleName());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiError>> handleUnexpected(Exception exception) {
        log.error("unexpected failure type={} message={}", exception.getClass().getName(), exception.getMessage(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected failure while serving the request");
    }

    private Mono<ResponseEntity<ApiError>> build(HttpStatus status, String code, String message) {
        return TraceContextHolder.current().map(traceContext -> ResponseEntity.status(status)
                .body(new ApiError(code, message, traceContext.requestId(), traceContext.correlationId(), Instant.now())));
    }
}
