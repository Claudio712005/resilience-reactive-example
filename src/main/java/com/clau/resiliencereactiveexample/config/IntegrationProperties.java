package com.clau.resiliencereactiveexample.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "catalog.integrations")
public record IntegrationProperties(@Valid Map<String, Endpoint> endpoints) {

    public Endpoint endpoint(String name) {
        Endpoint endpoint = endpoints.get(name);
        if (endpoint == null) {
            throw new IllegalStateException("No integration endpoint configured under catalog.integrations.endpoints." + name);
        }
        return endpoint;
    }

    public record Endpoint(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration responseTimeout,
            int maxConnections,
            @NotNull Duration pendingAcquireTimeout) {

        public Endpoint {
            if (maxConnections <= 0) {
                maxConnections = 50;
            }
        }
    }
}
