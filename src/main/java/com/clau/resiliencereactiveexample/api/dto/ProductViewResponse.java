package com.clau.resiliencereactiveexample.api.dto;

import com.clau.resiliencereactiveexample.domain.ProductView;

import java.math.BigDecimal;
import java.util.List;

public record ProductViewResponse(
        ProductResponse product,
        Price price,
        Stock stock,
        Reviews reviews,
        Serving serving) {

    public static ProductViewResponse from(ProductView view) {
        return new ProductViewResponse(
                ProductResponse.from(view.product()),
                new Price(view.price().finalPrice(), view.price().currency(),
                        view.price().discountPercentage(), view.price().source(), view.price().degraded()),
                new Stock(view.availability().quantity(), view.availability().warehouse(),
                        view.availability().inStock(), view.availability().source(), view.availability().degraded()),
                new Reviews(view.rating().average(), view.rating().reviewCount(),
                        view.rating().source(), view.rating().degraded()),
                new Serving(view.serving().servedFrom(), view.serving().cacheHit(),
                        view.serving().degradedSources(), view.serving().fullyHealthy()));
    }

    public record Price(BigDecimal finalPrice, String currency, BigDecimal discountPercentage,
                        String source, boolean degraded) {
    }

    public record Stock(int quantity, String warehouse, boolean inStock, String source, boolean degraded) {
    }

    public record Reviews(double average, int reviewCount, String source, boolean degraded) {
    }

    public record Serving(String servedFrom, boolean cacheHit, List<String> degradedSources, boolean fullyHealthy) {
    }
}
