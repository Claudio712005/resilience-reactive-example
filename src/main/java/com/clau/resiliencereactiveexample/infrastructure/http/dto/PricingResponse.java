package com.clau.resiliencereactiveexample.infrastructure.http.dto;

import java.math.BigDecimal;

public record PricingResponse(
        String sku,
        BigDecimal finalPrice,
        String currency,
        BigDecimal discountPercentage) {
}
