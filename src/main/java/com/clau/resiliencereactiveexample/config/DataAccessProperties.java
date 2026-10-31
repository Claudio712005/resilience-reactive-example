package com.clau.resiliencereactiveexample.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "catalog.data-access")
public record DataAccessProperties(int maxPageSize) {

    public DataAccessProperties {
        if (maxPageSize <= 0) {
            maxPageSize = 100;
        }
    }
}
