package com.example.agentplatform.security.audit;

import com.example.agentplatform.model.AlertRule;
import com.example.agentplatform.repository.AlertRuleRepository;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AlertService;
import com.example.agentplatform.service.AuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertRuleRepository alertRuleRepository;
    @Mock
    private OpenApiCallLogRepository callLogRepository;
    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private AuditRecorder auditRecorder;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertService(alertRuleRepository, callLogRepository, auditEventRepository, auditRecorder, new ObjectMapper());
    }

    @Test
    @DisplayName("告警规则校验: URL非法或阈值非法时拦截")
    void testSaveRuleValidation() {
        CurrentActor actor = new CurrentActor("dev-01", "developer", com.example.agentplatform.model.UserRole.DEVELOPER);
        AlertRule rule = new AlertRule();
        rule.setName("高危告警");
        rule.setMetric("HIGH_RISK_COUNT");
        rule.setThreshold(5.0);
        rule.setWebhookUrl("invalid-url"); // 不是 http:// 开头

        assertThatThrownBy(() -> alertService.saveRule(rule, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Webhook URL 格式不正确");
    }

    @Test
    @DisplayName("告警评估引擎: 超过高危阈值触发告警并更新触发时间")
    void testEvaluateAllRulesTriggered() {
        AlertRule rule = new AlertRule();
        rule.setId("rule-001");
        rule.setName("高危事件告警");
        rule.setMetric("HIGH_RISK_COUNT");
        rule.setThreshold(3.0);
        rule.setWebhookUrl("http://localhost:9999/webhook"); // 测试用地址
        rule.setEnabled(true);
        rule.setTimeWindowMinutes(15);
        rule.setSilenceMinutes(30);

        when(alertRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        // 模拟最近15分钟内发生了 5 次 HIGH 风险事件，超过阈值 3.0
        when(auditEventRepository.countByOccurredAtAfterAndRiskLevel(any(LocalDateTime.class), eq("HIGH"))).thenReturn(5L);
        when(alertRuleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));

        int triggered = alertService.evaluateAllRules();

        assertThat(triggered).isEqualTo(1);
        assertThat(rule.getLastTriggeredAt()).isNotNull();
        verify(alertRuleRepository).save(rule);
        verify(auditRecorder).record(eq("alert.triggered"), eq("ALERT_RULE"), eq("rule-001"), eq("TRIGGERED"), eq("HIGH_RISK_COUNT"), eq("HIGH"), anyString());
    }

    @Test
    @DisplayName("告警静默期防抖: 触发后静默期内不重复告警")
    void testSilenceWindowSuppression() {
        AlertRule rule = new AlertRule();
        rule.setId("rule-002");
        rule.setName("高危事件告警");
        rule.setMetric("HIGH_RISK_COUNT");
        rule.setThreshold(3.0);
        rule.setWebhookUrl("http://localhost:9999/webhook");
        rule.setEnabled(true);
        rule.setSilenceMinutes(30);
        // 上次触发在 10 分钟前，仍处于 30 分钟静默期内
        rule.setLastTriggeredAt(LocalDateTime.now().minusMinutes(10));

        when(alertRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule));

        int triggered = alertService.evaluateAllRules();

        // 静默期内不触发
        assertThat(triggered).isEqualTo(0);
        verify(auditEventRepository, never()).countByOccurredAtAfterAndRiskLevel(any(), any());
    }
}
