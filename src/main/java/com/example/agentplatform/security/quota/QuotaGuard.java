package com.example.agentplatform.security.quota;

import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.repository.UsageDailyRepository;
import com.example.agentplatform.security.OpenApiContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@Component
public class QuotaGuard {

    public record Result(boolean allowed, String code, String message) {
        public static Result allow() {
            return new Result(true, null, null);
        }
        public static Result deny(String code, String message) {
            return new Result(false, code, message);
        }
    }

    private final UsageDailyRepository usageDailyRepository;
    private final Cache<String, Long> tokenUsageCache;

    public QuotaGuard(UsageDailyRepository usageDailyRepository) {
        this.usageDailyRepository = usageDailyRepository;
        this.tokenUsageCache = Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.SECONDS)
                .maximumSize(10000)
                .build();
    }

    public Result checkQuota(OpenApiContext ctx) {
        if (ctx == null || ctx.getOwner() == null) {
            return Result.allow();
        }
        LocalDate today = LocalDate.now();

        // 1. 检查开发者账号每日配额
        GuardrailPolicy policy = ctx.getPolicy();
        if (policy != null && policy.getDefaultDailyTokens() != null && policy.getDefaultDailyTokens() > 0) {
            long quota = policy.getDefaultDailyTokens();
            String cacheKey = "owner:" + ctx.getOwner().getId() + ":" + today;
            long used = tokenUsageCache.get(cacheKey, k -> usageDailyRepository.getTodayTokensByOwner(ctx.getOwner().getId(), today));
            if (used >= quota) {
                return Result.deny("quota_exceeded", "已超出开发者今日 Token 配额上限 (" + quota + ")");
            }
        }

        // 2. 检查单台终端每日配额
        ClientCredential client = ctx.getClient();
        if (client != null && client.getDailyTokenQuota() != null && client.getDailyTokenQuota() > 0) {
            long clientQuota = client.getDailyTokenQuota();
            String cacheKey = "client:" + client.getId() + ":" + today;
            long clientUsed = tokenUsageCache.get(cacheKey, k -> usageDailyRepository.getTodayTokensByClient(client.getId(), today));
            if (clientUsed >= clientQuota) {
                return Result.deny("quota_exceeded", "已超出该接入终端今日 Token 配额上限 (" + clientQuota + ")");
            }
        }

        return Result.allow();
    }

    public void clearCache() {
        tokenUsageCache.invalidateAll();
    }
}
