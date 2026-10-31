package com.clau.resiliencereactiveexample.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TraceContext(
        String requestId,
        String correlationId,
        String transactionId,
        String sessionId,
        String originService,
        String operation) {

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TRANSACTION_ID = "transactionId";
    public static final String MDC_SESSION_ID = "sessionId";
    public static final String MDC_ORIGIN_SERVICE = "originService";
    public static final String MDC_OPERATION = "operation";

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRANSACTION_ID = "X-Transaction-Id";
    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String HEADER_ORIGIN_SERVICE = "X-Origin-Service";

    public static TraceContext create(String requestId,
                                      String correlationId,
                                      String transactionId,
                                      String sessionId,
                                      String originService,
                                      String operation) {
        return new TraceContext(
                orNew(requestId),
                orNew(correlationId),
                orNew(transactionId),
                orDefault(sessionId, "anonymous"),
                orDefault(originService, "unknown"),
                orDefault(operation, "unknown"));
    }

    public TraceContext withOperation(String newOperation) {
        return new TraceContext(requestId, correlationId, transactionId, sessionId, originService, newOperation);
    }

    public Map<String, String> asMdc() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MDC_REQUEST_ID, requestId);
        values.put(MDC_CORRELATION_ID, correlationId);
        values.put(MDC_TRANSACTION_ID, transactionId);
        values.put(MDC_SESSION_ID, sessionId);
        values.put(MDC_ORIGIN_SERVICE, originService);
        values.put(MDC_OPERATION, operation);
        return values;
    }

    private static String orNew(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
