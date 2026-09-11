package com.example.agentplatform.security.ratelimit;

public interface RateLimiter {

    record Result(boolean allowed, long limit, long remaining, long resetSeconds) {}

    Result tryConsume(String key, int permits, int rpm);
}
