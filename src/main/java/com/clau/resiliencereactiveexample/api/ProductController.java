package com.clau.resiliencereactiveexample.api;

import com.clau.resiliencereactiveexample.api.dto.CreateProductRequest;
import com.clau.resiliencereactiveexample.api.dto.ProductResponse;
import com.clau.resiliencereactiveexample.api.dto.ProductViewResponse;
import com.clau.resiliencereactiveexample.application.ProductCatalogService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductCatalogService service;

    public ProductController(ProductCatalogService service) {
        this.service = service;
    }

    @GetMapping("/{sku}")
    public Mono<ProductViewResponse> findBySku(@PathVariable String sku) {
        return service.findViewBySku(sku).map(ProductViewResponse::from);
    }

    @GetMapping("/views")
    public Flux<ProductViewResponse> findViews(@RequestParam List<String> skus) {
        return service.findViews(skus).map(ProductViewResponse::from);
    }

    @GetMapping
    public Flux<ProductResponse> findPage(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.findPage(page, size).map(ProductResponse::from);
    }

    @GetMapping("/count")
    public Mono<Long> countActive() {
        return service.countActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResponseEntity<ProductResponse>> create(@Valid @RequestBody CreateProductRequest request) {
        return service.create(request.toDomain())
                .map(ProductResponse::from)
                .map(created -> ResponseEntity
                        .created(URI.create("/api/v1/products/" + created.sku()))
                        .body(created));
    }

    @DeleteMapping("/{sku}/cache")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> evictCache(@PathVariable String sku) {
        return service.evict(sku);
    }
}
