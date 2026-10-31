package com.clau.resiliencereactiveexample.domain.port;

import com.clau.resiliencereactiveexample.domain.Rating;
import reactor.core.publisher.Mono;

public interface RatingPort {

    Mono<Rating> rating(String sku);
}
