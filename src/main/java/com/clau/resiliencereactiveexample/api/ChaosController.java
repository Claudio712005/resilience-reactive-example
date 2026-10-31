package com.clau.resiliencereactiveexample.api;

import com.clau.resiliencereactiveexample.infrastructure.chaos.ChaosSwitch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/chaos")
public class ChaosController {

    private final ChaosSwitch chaosSwitch;

    public ChaosController(ChaosSwitch chaosSwitch) {
        this.chaosSwitch = chaosSwitch;
    }

    @GetMapping
    public Mono<Map<String, Object>> state() {
        return Mono.just(chaosSwitch.state());
    }

    @PostMapping("/redis")
    public Mono<Map<String, Object>> redis(@RequestParam boolean unavailable) {
        chaosSwitch.setRedisUnavailable(unavailable);
        return Mono.just(chaosSwitch.state());
    }

    @PostMapping("/database")
    public Mono<Map<String, Object>> database(@RequestParam(defaultValue = "false") boolean unavailable,
                                              @RequestParam(defaultValue = "0") long latencyMillis) {
        chaosSwitch.setDatabaseUnavailable(unavailable);
        chaosSwitch.setDatabaseLatencyMillis(latencyMillis);
        return Mono.just(chaosSwitch.state());
    }

    @PostMapping("/reset")
    public Mono<Map<String, Object>> reset() {
        chaosSwitch.reset();
        return Mono.just(chaosSwitch.state());
    }
}
