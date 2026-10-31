package com.clau.resiliencereactiveexample.domain.port;

import com.clau.resiliencereactiveexample.domain.PriceQuote;
import reactor.core.publisher.Mono;

public interface PricingPort {

    Mono<PriceQuote> quote(String sku);
}
