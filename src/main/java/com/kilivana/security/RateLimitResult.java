package com.kilivana.security;

public record RateLimitResult(Status status, long retryAfterSeconds) {

    public enum Status {
        ALLOWED,
        LIMITED,
        UNAVAILABLE
    }

    public static RateLimitResult allowed() {
        return new RateLimitResult(Status.ALLOWED, 0);
    }

    public static RateLimitResult limited(long retryAfterSeconds) {
        return new RateLimitResult(Status.LIMITED, Math.max(1, retryAfterSeconds));
    }

    public static RateLimitResult unavailable() {
        return new RateLimitResult(Status.UNAVAILABLE, 1);
    }
}
