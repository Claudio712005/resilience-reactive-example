package com.clau.resiliencereactiveexample.infrastructure.cache;

public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message) {
        super(message);
    }
}
