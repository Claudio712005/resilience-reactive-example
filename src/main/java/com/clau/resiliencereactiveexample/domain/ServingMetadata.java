package com.clau.resiliencereactiveexample.domain;

import java.util.List;

public record ServingMetadata(
        String servedFrom,
        boolean cacheHit,
        List<String> degradedSources) {

    public static ServingMetadata of(String servedFrom, boolean cacheHit, List<String> degradedSources) {
        return new ServingMetadata(servedFrom, cacheHit, degradedSources == null ? List.of() : List.copyOf(degradedSources));
    }

    public boolean fullyHealthy() {
        return degradedSources.isEmpty();
    }
}
