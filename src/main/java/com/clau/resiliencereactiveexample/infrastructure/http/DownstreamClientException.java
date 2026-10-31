package com.clau.resiliencereactiveexample.infrastructure.http;

public class DownstreamClientException extends RuntimeException {

    private final String serviceName;
    private final int statusCode;

    public DownstreamClientException(String serviceName, int statusCode, String message) {
        super("Downstream " + serviceName + " rejected the call with status " + statusCode + ": " + message);
        this.serviceName = serviceName;
        this.statusCode = statusCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
