package com.clau.resiliencereactiveexample.infrastructure.http.dto;

public record RatingResponse(
        String sku,
        Double average,
        Integer reviewCount) {
}
