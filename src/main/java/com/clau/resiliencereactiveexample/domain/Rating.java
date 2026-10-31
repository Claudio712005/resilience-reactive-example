package com.clau.resiliencereactiveexample.domain;

public record Rating(
        String sku,
        double average,
        int reviewCount,
        String source,
        boolean degraded) {

    public static Rating unrated(String sku) {
        return new Rating(sku, 0.0d, 0, "fallback", true);
    }
}
