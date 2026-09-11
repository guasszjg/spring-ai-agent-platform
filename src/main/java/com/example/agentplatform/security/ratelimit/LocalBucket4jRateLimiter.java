package com.example.agentplatform.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class LocalBucket4jRateLimiter implements RateLimiter {

    private record Entry(Bucket bucket, int rpm) {}

    private final Cache<String, Entry> buckets = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(50000)
            .build();

    @Override
    public Result tryConsume(String key, int permits, int rpm) {
        if (rpm <= 0) {
            return new Result(true, 0, 0, 0);
        }
        Entry entry = buckets.get(key, k -> createEntry(rpm));
        if (entry.rpm() != rpm) {
            entry = createEntry(rpm);
            buckets.put(key, entry);
        }

        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(permits);
        boolean allowed = probe.isConsumed();
        long remaining = Math.max(0, probe.getRemainingTokens());
        long resetSeconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));

        return new Result(allowed, rpm, remaining, resetSeconds);
    }

    private Entry createEntry(int rpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rpm)
                .refillGreedy(rpm, Duration.ofMinutes(1))
                .build();
        Bucket bucket = Bucket.builder()
                .addLimit(limit)
                .build();
        return new Entry(bucket, rpm);
    }

    public void clear() {
        buckets.invalidateAll();
    }
}
