package com.clau.resiliencereactiveexample.infrastructure.chaos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class ChaosSwitch {

    private final AtomicBoolean redisUnavailable = new AtomicBoolean(false);
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean(false);
    private final AtomicLong databaseLatencyMillis = new AtomicLong(0);

    public boolean isRedisUnavailable() {
        return redisUnavailable.get();
    }

    public boolean isDatabaseUnavailable() {
        return databaseUnavailable.get();
    }

    public long databaseLatencyMillis() {
        return databaseLatencyMillis.get();
    }

    public void setRedisUnavailable(boolean value) {
        redisUnavailable.set(value);
        log.warn("chaos toggle redisUnavailable={}", value);
    }

    public void setDatabaseUnavailable(boolean value) {
        databaseUnavailable.set(value);
        log.warn("chaos toggle databaseUnavailable={}", value);
    }

    public void setDatabaseLatencyMillis(long value) {
        databaseLatencyMillis.set(Math.max(0, value));
        log.warn("chaos toggle databaseLatencyMillis={}", value);
    }

    public void reset() {
        redisUnavailable.set(false);
        databaseUnavailable.set(false);
        databaseLatencyMillis.set(0);
        log.warn("chaos toggles reset");
    }

    public Map<String, Object> state() {
        return Map.of(
                "redisUnavailable", redisUnavailable.get(),
                "databaseUnavailable", databaseUnavailable.get(),
                "databaseLatencyMillis", databaseLatencyMillis.get());
    }
}
