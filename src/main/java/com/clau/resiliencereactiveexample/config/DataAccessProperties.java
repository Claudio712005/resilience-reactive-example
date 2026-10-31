package com.clau.resiliencereactiveexample.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "catalog.data-access")
public record DataAccessProperties(
        @NotNull Duration readTimeout,
        @NotNull Duration writeTimeout,
        @NotNull Duration listTimeout,
        int maxPageSize) {

    public DataAccessProperties {
        if (maxPageSize <= 0) {
            maxPageSize = 100;
        }
    }
}
