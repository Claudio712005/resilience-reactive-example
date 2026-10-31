package com.clau.resiliencereactiveexample.domain;

public record Availability(
        String sku,
        int quantity,
        String warehouse,
        boolean inStock,
        String source,
        boolean degraded) {

    public static Availability unknown(String sku) {
        return new Availability(sku, 0, "unknown", false, "fallback", true);
    }
}
