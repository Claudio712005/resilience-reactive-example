package com.clau.resiliencereactiveexample.domain;

import java.util.ArrayList;
import java.util.List;

public record ProductView(
        Product product,
        PriceQuote price,
        Availability availability,
        Rating rating,
        ServingMetadata serving) {

    public static ProductView compose(Product product,
                                      PriceQuote price,
                                      Availability availability,
                                      Rating rating,
                                      String servedFrom,
                                      boolean cacheHit) {
        List<String> degraded = new ArrayList<>();
        if (price.degraded()) {
            degraded.add("pricing");
        }
        if (availability.degraded()) {
            degraded.add("inventory");
        }
        if (rating.degraded()) {
            degraded.add("rating");
        }
        return new ProductView(product, price, availability, rating,
                ServingMetadata.of(servedFrom, cacheHit, degraded));
    }

    public ProductView servedFrom(String servedFrom, boolean cacheHit) {
        return new ProductView(product, price, availability, rating,
                ServingMetadata.of(servedFrom, cacheHit, serving == null ? List.of() : serving.degradedSources()));
    }
}
