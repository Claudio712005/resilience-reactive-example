package com.clau.resiliencereactiveexample.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "catalog.cache")
public record CacheProperties(
        boolean enabled,
        @NotNull Duration remoteTtl,
        @NotNull Duration remoteTimeout,
        @NotNull Duration writeTimeout,
        boolean localEnabled,
        @NotNull Duration localTtl,
        long localMaxSize,
        @NotNull String keyPrefix) {

    public CacheProperties {
        if (localMaxSize <= 0) {
            localMaxSize = 10_000;
        }
    }

    public String remoteKey(String key) {
        return keyPrefix + ":" + key;
    }
}
