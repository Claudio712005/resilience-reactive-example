package com.clau.resiliencereactiveexample.api.dto;

import com.clau.resiliencereactiveexample.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        String category,
        String brand,
        BigDecimal basePrice,
        Instant createdAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id(),
                product.sku(),
                product.name(),
                product.description(),
                product.category(),
                product.brand(),
                product.basePrice(),
                product.createdAt());
    }
}
