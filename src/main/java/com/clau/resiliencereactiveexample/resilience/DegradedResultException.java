package com.clau.resiliencereactiveexample.resilience;

public class DegradedResultException extends RuntimeException {

    private final String resilienceName;

    public DegradedResultException(String resilienceName, String message, Throwable cause) {
        super(message, cause);
        this.resilienceName = resilienceName;
    }

    public String getResilienceName() {
        return resilienceName;
    }
}
