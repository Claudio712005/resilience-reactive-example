package com.clau.resiliencereactiveexample.domain;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductViewSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void shouldRoundTripThroughJsonSoTheCacheCanRehydrateTheView() {
        Product product = Product.newProduct("SKU-NOTE-0001", "Notebook Pro 14", "14 polegadas",
                "informatica", "Nexora", new BigDecimal("8999.90"));
        ProductView original = ProductView.compose(
                product,
                new PriceQuote("SKU-NOTE-0001", new BigDecimal("1299.90"), "BRL", BigDecimal.TEN, "pricing-service", false),
                new Availability("SKU-NOTE-0001", 42, "SP-01", true, "inventory-service", false),
                new Rating("SKU-NOTE-0001", 4.6d, 318, "rating-service", false),
                "database-and-downstreams",
                false);

        String json = jsonMapper.writeValueAsString(original);
        ProductView restored = jsonMapper.readValue(json, ProductView.class);

        assertThat(restored.product().sku()).isEqualTo(original.product().sku());
        assertThat(restored.product().createdAt()).isEqualTo(original.product().createdAt());
        assertThat(restored.price().finalPrice()).isEqualByComparingTo(original.price().finalPrice());
        assertThat(restored.availability().quantity()).isEqualTo(42);
        assertThat(restored.rating().average()).isEqualTo(4.6d);
        assertThat(restored.serving().servedFrom()).isEqualTo("database-and-downstreams");
        assertThat(restored.serving().degradedSources()).isEmpty();
        assertThat(restored.serving().fullyHealthy()).isTrue();
    }

    @Test
    void shouldKeepDegradedSourcesAcrossSerialization() {
        Product product = Product.newProduct("SKU-FAIL-9002", "Produto instavel", null,
                "laboratorio", "ChaosLab", new BigDecimal("299.90"));
        ProductView original = ProductView.compose(
                product,
                PriceQuote.fromCatalogBasePrice("SKU-FAIL-9002", new BigDecimal("299.90")),
                Availability.unknown("SKU-FAIL-9002"),
                Rating.unrated("SKU-FAIL-9002"),
                "database-and-downstreams",
                false);

        ProductView restored = jsonMapper.readValue(jsonMapper.writeValueAsString(original), ProductView.class);

        assertThat(restored.serving().degradedSources()).containsExactly("pricing", "inventory", "rating");
        assertThat(restored.serving().fullyHealthy()).isFalse();
    }
}
