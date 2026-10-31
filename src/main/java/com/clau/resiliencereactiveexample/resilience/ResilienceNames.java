package com.clau.resiliencereactiveexample.resilience;

public final class ResilienceNames {

    public static final String PRICING_SERVICE = "pricing-service";
    public static final String INVENTORY_SERVICE = "inventory-service";
    public static final String RATING_SERVICE = "rating-service";
    public static final String PRODUCT_DATABASE_READ = "product-database-read";
    public static final String PRODUCT_DATABASE_LIST = "product-database-list";
    public static final String PRODUCT_DATABASE_WRITE = "product-database-write";
    public static final String PRODUCT_CACHE = "product-cache";

    private ResilienceNames() {
    }
}
