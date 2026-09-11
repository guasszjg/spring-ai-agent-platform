package com.example.agentplatform.security.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBucket4jRateLimiterTest {

    private LocalBucket4jRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LocalBucket4jRateLimiter();
    }

    @Test
    @DisplayName("RPM 额度内请求被允许，且 remaining 逐次递减")
    void tryConsume_withinLimit() {
        String key = "test_key_1";
        int rpm = 5;

        for (int i = 0; i < 5; i++) {
            RateLimiter.Result res = rateLimiter.tryConsume(key, 1, rpm);
            assertThat(res.allowed()).isTrue();
            assertThat(res.limit()).isEqualTo(5);
            assertThat(res.remaining()).isEqualTo(4 - i);
        }

        // 第 6 次超出 5 RPM 限制，应被拒绝
        RateLimiter.Result overflow = rateLimiter.tryConsume(key, 1, rpm);
        assertThat(overflow.allowed()).isFalse();
        assertThat(overflow.remaining()).isEqualTo(0);
        assertThat(overflow.resetSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("不同 key 之间的令牌桶相互隔离")
    void tryConsume_keyIsolation() {
        String key1 = "client_001";
        String key2 = "client_002";

        // key1 消耗全部 2 个令牌
        assertThat(rateLimiter.tryConsume(key1, 2, 2).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(key1, 1, 2).allowed()).isFalse();

        // key2 仍拥有全部令牌
        RateLimiter.Result res2 = rateLimiter.tryConsume(key2, 1, 2);
        assertThat(res2.allowed()).isTrue();
        assertThat(res2.remaining()).isEqualTo(1);
    }
}
