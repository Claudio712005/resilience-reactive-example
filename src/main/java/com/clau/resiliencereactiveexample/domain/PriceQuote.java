package com.clau.resiliencereactiveexample.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PriceQuote(
        String sku,
        BigDecimal finalPrice,
        String currency,
        BigDecimal discountPercentage,
        String source,
        boolean degraded) {

    public static PriceQuote fromCatalogBasePrice(String sku, BigDecimal basePrice) {
        return new PriceQuote(
                sku,
                basePrice == null ? BigDecimal.ZERO : basePrice.setScale(2, RoundingMode.HALF_UP),
                "BRL",
                BigDecimal.ZERO,
                "catalog-base-price",
                true);
    }
}
