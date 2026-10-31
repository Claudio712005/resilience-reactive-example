package com.clau.resiliencereactiveexample.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String name,
        String description,
        String category,
        String brand,
        BigDecimal basePrice,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static Product newProduct(String sku,
                                     String name,
                                     String description,
                                     String category,
                                     String brand,
                                     BigDecimal basePrice) {
        Instant now = Instant.now();
        return new Product(UUID.randomUUID(), sku, name, description, category, brand, basePrice, true, now, now);
    }
}
