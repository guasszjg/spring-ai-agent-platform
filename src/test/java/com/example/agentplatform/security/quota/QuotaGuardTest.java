package com.example.agentplatform.security.quota;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ClientCredential;
import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.repository.UsageDailyRepository;
import com.example.agentplatform.security.OpenApiContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaGuardTest {

    @Mock
    private UsageDailyRepository usageDailyRepository;

    private QuotaGuard quotaGuard;
    private AppUser owner;
    private OpenApiKey key;

    @BeforeEach
    void setUp() {
        quotaGuard = new QuotaGuard(usageDailyRepository);
        owner = new AppUser();
        owner.setId("usr-quota-1");
        key = new OpenApiKey();
        key.setId("oak-quota-1");
    }

    @Test
    @DisplayName("未超配额时放行")
    void checkQuota_allowed() {
        OpenApiContext ctx = new OpenApiContext("req-1", key, owner, "127.0.0.1");
        GuardrailPolicy policy = new GuardrailPolicy();
        policy.setDefaultDailyTokens(10000L);
        ctx.setPolicy(policy);

        when(usageDailyRepository.getTodayTokensByOwner(eq("usr-quota-1"), any(LocalDate.class)))
                .thenReturn(5000L);

        QuotaGuard.Result res = quotaGuard.checkQuota(ctx);
        assertThat(res.allowed()).isTrue();
    }

    @Test
    @DisplayName("开发者当日 Token 达到配额上限时熔断拒绝")
    void checkQuota_ownerExceeded() {
        OpenApiContext ctx = new OpenApiContext("req-2", key, owner, "127.0.0.1");
        GuardrailPolicy policy = new GuardrailPolicy();
        policy.setDefaultDailyTokens(10000L);
        ctx.setPolicy(policy);

        when(usageDailyRepository.getTodayTokensByOwner(eq("usr-quota-1"), any(LocalDate.class)))
                .thenReturn(10000L);

        QuotaGuard.Result res = quotaGuard.checkQuota(ctx);
        assertThat(res.allowed()).isFalse();
        assertThat(res.code()).isEqualTo("quota_exceeded");
        assertThat(res.message()).contains("已超出开发者今日 Token 配额上限");
    }

    @Test
    @DisplayName("终端当日 Token 达到单设备配额上限时熔断拒绝")
    void checkQuota_clientExceeded() {
        OpenApiContext ctx = new OpenApiContext("req-3", key, owner, "127.0.0.1");
        GuardrailPolicy policy = new GuardrailPolicy();
        policy.setDefaultDailyTokens(100000L); // 开发者总额度未超
        ctx.setPolicy(policy);

        ClientCredential client = new ClientCredential();
        client.setId("cli-sn-001");
        client.setDailyTokenQuota(2000L);
        ctx.setClient(client);

        when(usageDailyRepository.getTodayTokensByOwner(eq("usr-quota-1"), any(LocalDate.class)))
                .thenReturn(5000L);
        when(usageDailyRepository.getTodayTokensByClient(eq("cli-sn-001"), any(LocalDate.class)))
                .thenReturn(2500L);

        QuotaGuard.Result res = quotaGuard.checkQuota(ctx);
        assertThat(res.allowed()).isFalse();
        assertThat(res.code()).isEqualTo("quota_exceeded");
        assertThat(res.message()).contains("已超出该接入终端今日 Token 配额上限");
    }
}
