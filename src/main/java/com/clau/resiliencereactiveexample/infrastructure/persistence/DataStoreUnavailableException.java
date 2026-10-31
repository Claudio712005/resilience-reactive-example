package com.clau.resiliencereactiveexample.infrastructure.persistence;

public class DataStoreUnavailableException extends RuntimeException {

    public DataStoreUnavailableException(String message) {
        super(message);
    }
}
