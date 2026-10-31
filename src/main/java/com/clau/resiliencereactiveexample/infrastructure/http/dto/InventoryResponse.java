package com.clau.resiliencereactiveexample.infrastructure.http.dto;

public record InventoryResponse(
        String sku,
        Integer quantity,
        String warehouse) {
}
