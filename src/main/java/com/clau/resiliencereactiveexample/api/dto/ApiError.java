package com.clau.resiliencereactiveexample.api.dto;

import java.time.Instant;

public record ApiError(
        String code,
        String message,
        String requestId,
        String correlationId,
        Instant timestamp) {
}
