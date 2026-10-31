package com.clau.resiliencereactiveexample.domain.port;

import com.clau.resiliencereactiveexample.domain.Availability;
import reactor.core.publisher.Mono;

public interface InventoryPort {

    Mono<Availability> availability(String sku);
}
