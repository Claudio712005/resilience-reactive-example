package com.clau.resiliencereactiveexample.application;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("No active product found for sku " + sku);
    }
}
