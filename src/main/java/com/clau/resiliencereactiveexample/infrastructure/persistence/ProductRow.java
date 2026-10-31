package com.clau.resiliencereactiveexample.infrastructure.persistence;

import com.clau.resiliencereactiveexample.domain.Product;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("products")
public record ProductRow(
        @Id UUID id,
        @Column("sku") String sku,
        @Column("name") String name,
        @Column("description") String description,
        @Column("category") String category,
        @Column("brand") String brand,
        @Column("base_price") BigDecimal basePrice,
        @Column("active") boolean active,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {

    public static ProductRow fromDomain(Product product) {
        return new ProductRow(
                product.id(),
                product.sku(),
                product.name(),
                product.description(),
                product.category(),
                product.brand(),
                product.basePrice(),
                product.active(),
                product.createdAt(),
                product.updatedAt());
    }

    public Product toDomain() {
        return new Product(id, sku, name, description, category, brand, basePrice, active, createdAt, updatedAt);
    }
}
