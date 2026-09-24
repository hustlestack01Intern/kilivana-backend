package com.kilivana.security;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kilivana.security.rate-limit", name = "backend", havingValue = "redis", matchIfMissing = true)
public class RedisRateLimiter implements RateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); "
                    + "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return n;",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TokenService tokenService;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, TokenService tokenService) {
        this.redisTemplate = redisTemplate;
        this.tokenService = tokenService;
    }

    @Override
    public RateLimitResult tryConsume(String key, int limit, Duration window) {
        String redisKey = "kilivana:rate-limit:" + tokenService.hash(key);
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(redisKey),
                    Long.toString(Math.max(1, window.toSeconds())));
            if (count == null) {
                return RateLimitResult.unavailable();
            }
            if (count > limit) {
                Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
                return RateLimitResult.limited(ttl == null || ttl < 1 ? window.toSeconds() : ttl);
            }
            return RateLimitResult.allowed();
        } catch (RuntimeException exception) {
            return RateLimitResult.unavailable();
        }
    }
}
