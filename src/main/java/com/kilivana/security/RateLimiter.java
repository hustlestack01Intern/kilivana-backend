package com.kilivana.security;

import java.time.Duration;

public interface RateLimiter {

    RateLimitResult tryConsume(String key, int limit, Duration window);
}
