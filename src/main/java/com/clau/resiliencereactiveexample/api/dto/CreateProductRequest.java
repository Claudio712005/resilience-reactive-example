package com.clau.resiliencereactiveexample.api.dto;

import com.clau.resiliencereactiveexample.domain.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{4,32}") String sku,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 80) String category,
        @NotBlank @Size(max = 80) String brand,
        @NotNull @DecimalMin("0.00") BigDecimal basePrice) {

    public Product toDomain() {
        return Product.newProduct(sku, name, description, category, brand, basePrice);
    }
}
