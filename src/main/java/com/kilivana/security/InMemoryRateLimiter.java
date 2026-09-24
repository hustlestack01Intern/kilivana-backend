package com.kilivana.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kilivana.security.rate-limit", name = "backend", havingValue = "memory")
public class InMemoryRateLimiter implements RateLimiter {

    private final int maxKeys;
    private final Map<String, WindowState> windows = new LinkedHashMap<>();

    @Autowired
    public InMemoryRateLimiter(com.kilivana.security.config.RateLimitProperties properties) {
        this(properties.maxLocalKeys());
    }

    public InMemoryRateLimiter(int maxKeys) {
        this.maxKeys = Math.max(1, maxKeys);
    }

    @Override
    public synchronized RateLimitResult tryConsume(String key, int limit, Duration window) {
        Instant now = Instant.now();
        removeExpired(now, window);
        if (!windows.containsKey(key) && windows.size() >= maxKeys) {
            Iterator<String> iterator = windows.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        WindowState state = windows.computeIfAbsent(key, ignored -> new WindowState(now, 0));
        state.count++;
        if (state.count > limit) {
            long retryAfter = Math.max(1, window.getSeconds() - (now.getEpochSecond() - state.startedAt.getEpochSecond()));
            return RateLimitResult.limited(retryAfter);
        }
        return RateLimitResult.allowed();
    }

    private void removeExpired(Instant now, Duration window) {
        Iterator<Map.Entry<String, WindowState>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            WindowState state = iterator.next().getValue();
            if (!state.startedAt.plus(window).isAfter(now)) {
                iterator.remove();
            }
        }
    }

    private static final class WindowState {
        private final Instant startedAt;
        private int count;

        private WindowState(Instant startedAt, int count) {
            this.startedAt = startedAt;
            this.count = count;
        }
    }
}
