package com.clau.resiliencereactiveexample.infrastructure.persistence;

public class DuplicatedSkuException extends RuntimeException {

    public DuplicatedSkuException(String sku) {
        super("Product already registered for sku " + sku);
    }
}
