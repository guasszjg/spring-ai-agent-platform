package com.example.agentplatform.service;

import com.example.agentplatform.model.AlertRule;
import com.example.agentplatform.repository.AlertRuleRepository;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRuleRepository alertRuleRepository;
    private final OpenApiCallLogRepository callLogRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AlertService(AlertRuleRepository alertRuleRepository,
                        OpenApiCallLogRepository callLogRepository,
                        AuditEventRepository auditEventRepository,
                        AuditRecorder auditRecorder,
                        ObjectMapper objectMapper) {
        this.alertRuleRepository = alertRuleRepository;
        this.callLogRepository = callLogRepository;
        this.auditEventRepository = auditEventRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Transactional(readOnly = true)
    public List<AlertRule> listRules(CurrentActor actor) {
        if (actor == null) return List.of();
        if (actor.isSuperAdmin()) {
            return alertRuleRepository.findAllByOrderByCreatedAtDesc();
        }
        return alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(actor.getUserId());
    }

    @Transactional
    public AlertRule saveRule(AlertRule incoming, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权配置告警规则");
        }
        if (incoming.getName() == null || incoming.getName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (incoming.getMetric() == null || incoming.getMetric().isBlank()) {
            throw new IllegalArgumentException("监控指标不能为空");
        }
        if (incoming.getThreshold() == null || incoming.getThreshold() <= 0) {
            throw new IllegalArgumentException("告警阈值必须大于 0");
        }
        if (incoming.getWebhookUrl() == null || (!incoming.getWebhookUrl().startsWith("http://") && !incoming.getWebhookUrl().startsWith("https://"))) {
            throw new IllegalArgumentException("Webhook URL 格式不正确，需以 http:// 或 https:// 开头");
        }

        String ownerId = "GLOBAL";
        if (!actor.isSuperAdmin()) {
            ownerId = actor.getUserId();
        } else if (incoming.getOwnerId() != null && !incoming.getOwnerId().isBlank()) {
            ownerId = incoming.getOwnerId().trim();
        }

        AlertRule entity;
        if (incoming.getId() != null && !incoming.getId().isBlank()) {
            entity = alertRuleRepository.findById(incoming.getId())
                    .orElseThrow(() -> new IllegalArgumentException("告警规则不存在"));
            if (!actor.isSuperAdmin() && !entity.getOwnerId().equals(actor.getUserId())) {
                throw new IllegalStateException("无权修改此规则");
            }
        } else {
            entity = new AlertRule();
        }

        entity.setOwnerId(ownerId);
        entity.setName(incoming.getName().trim());
        entity.setMetric(incoming.getMetric().trim().toUpperCase());
        entity.setThreshold(incoming.getThreshold());
        entity.setTimeWindowMinutes(incoming.getTimeWindowMinutes() != null && incoming.getTimeWindowMinutes() > 0 ? incoming.getTimeWindowMinutes() : 15);
        entity.setWebhookUrl(incoming.getWebhookUrl().trim());
        entity.setWebhookSecret(incoming.getWebhookSecret() != null ? incoming.getWebhookSecret().trim() : null);
        entity.setEnabled(incoming.getEnabled() != null ? incoming.getEnabled() : true);
        entity.setSilenceMinutes(incoming.getSilenceMinutes() != null && incoming.getSilenceMinutes() > 0 ? incoming.getSilenceMinutes() : 30);

        AlertRule saved = alertRuleRepository.save(entity);
        auditRecorder.record("alert.rule_save", "ALERT_RULE", saved.getId(), "SUCCESS", null, "LOW", saved.getName());
        return saved;
    }

    @Transactional
    public void deleteRule(String id, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("无权删除告警规则");
        }
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在"));
        if (!actor.isSuperAdmin() && !rule.getOwnerId().equals(actor.getUserId())) {
            throw new IllegalStateException("无权删除该规则");
        }
        alertRuleRepository.delete(rule);
        auditRecorder.record("alert.rule_delete", "ALERT_RULE", id, "SUCCESS", null, "MEDIUM", rule.getName());
    }

    public Map<String, Object> testWebhook(String id, CurrentActor actor) {
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在"));
        if (actor != null && !actor.isSuperAdmin() && !rule.getOwnerId().equals(actor.getUserId())) {
            throw new IllegalStateException("无权操作此规则");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "alert.test");
        payload.put("ruleId", rule.getId());
        payload.put("ruleName", rule.getName());
        payload.put("metric", rule.getMetric());
        payload.put("threshold", rule.getThreshold());
        payload.put("timestamp", System.currentTimeMillis() / 1000);
        payload.put("message", "这是一条由平台发出的告警规则连通性测试消息");

        return sendWebhook(rule.getWebhookUrl(), rule.getWebhookSecret(), payload);
    }

    @Transactional
    public int evaluateAllRules() {
        List<AlertRule> activeRules = alertRuleRepository.findByEnabledTrue();
        int triggeredCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (AlertRule rule : activeRules) {
            try {
                // Check silence window
                if (rule.getLastTriggeredAt() != null) {
                    LocalDateTime quietUntil = rule.getLastTriggeredAt().plusMinutes(rule.getSilenceMinutes());
                    if (now.isBefore(quietUntil)) {
                        continue;
                    }
                }

                int windowMins = rule.getTimeWindowMinutes() != null && rule.getTimeWindowMinutes() > 0 ? rule.getTimeWindowMinutes() : 15;
                LocalDateTime windowStart = now.minusMinutes(windowMins);
                boolean isGlobal = "GLOBAL".equalsIgnoreCase(rule.getOwnerId());
                String ownerId = rule.getOwnerId();

                double currentValue = 0.0;
                boolean triggered = false;

                switch (rule.getMetric()) {
                    case "CALL_DENIED_RATE" -> {
                        long totalCalls = isGlobal ? callLogRepository.countByTsAfter(windowStart)
                                                   : callLogRepository.countByOwnerIdAndTsAfter(ownerId, windowStart);
                        if (totalCalls >= 5) {
                            long errors = isGlobal ? callLogRepository.countByHttpStatusGreaterThanEqualAndTsAfter(400, windowStart)
                                                   : callLogRepository.countByOwnerIdAndHttpStatusGreaterThanEqualAndTsAfter(ownerId, 400, windowStart);
                            currentValue = (double) errors / totalCalls;
                            if (currentValue >= rule.getThreshold()) {
                                triggered = true;
                            }
                        }
                    }
                    case "HIGH_RISK_COUNT" -> {
                        long highRisk = isGlobal ? auditEventRepository.countByOccurredAtAfterAndRiskLevel(windowStart, "HIGH")
                                                 : auditEventRepository.countByOwnerIdAndOccurredAtAfterAndRiskLevel(ownerId, windowStart, "HIGH");
                        currentValue = highRisk;
                        if (currentValue >= rule.getThreshold()) {
                            triggered = true;
                        }
                    }
                    case "QUOTA_EXCEEDED_COUNT" -> {
                        long quotaExceeded = isGlobal ? callLogRepository.countByHttpStatusAndTsAfter(429, windowStart)
                                                      : callLogRepository.countByOwnerIdAndHttpStatusAndTsAfter(ownerId, 429, windowStart);
                        currentValue = quotaExceeded;
                        if (currentValue >= rule.getThreshold()) {
                            triggered = true;
                        }
                    }
                    default -> log.warn("Unknown alert metric: {}", rule.getMetric());
                }

                if (triggered) {
                    triggeredCount++;
                    log.warn("Alert rule triggered: name={}, metric={}, current={}, threshold={}",
                            rule.getName(), rule.getMetric(), currentValue, rule.getThreshold());

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("event", "alert.triggered");
                    payload.put("ruleId", rule.getId());
                    payload.put("ruleName", rule.getName());
                    payload.put("metric", rule.getMetric());
                    payload.put("threshold", rule.getThreshold());
                    payload.put("currentValue", currentValue);
                    payload.put("windowMinutes", windowMins);
                    payload.put("timestamp", System.currentTimeMillis() / 1000);
                    payload.put("message", String.format("告警触发: [%s] 指标达到 %.2f，已超过设定阈值 %.2f", rule.getName(), currentValue, rule.getThreshold()));

                    sendWebhook(rule.getWebhookUrl(), rule.getWebhookSecret(), payload);

                    rule.setLastTriggeredAt(now);
                    alertRuleRepository.save(rule);

                    auditRecorder.record("alert.triggered", "ALERT_RULE", rule.getId(), "TRIGGERED",
                            rule.getMetric(), "HIGH", String.format("Current=%.2f, Threshold=%.2f", currentValue, rule.getThreshold()));
                }
            } catch (Exception e) {
                log.error("Failed to evaluate rule: id={}, err={}", rule.getId(), e.getMessage(), e);
            }
        }
        return triggeredCount;
    }

    private Map<String, Object> sendWebhook(String webhookUrl, String secret, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "AgentPlatform-AlertBot/1.0");

            if (secret != null && !secret.isBlank()) {
                String signature = computeHmacSha256(jsonBody, secret);
                reqBuilder.header("X-Alert-Signature", signature);
            }

            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            long latency = System.currentTimeMillis() - start;
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            result.put("success", ok);
            result.put("statusCode", response.statusCode());
            result.put("latencyMs", latency);
            result.put("responseBody", response.body() != null && response.body().length() > 500 ? response.body().substring(0, 500) : response.body());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("latencyMs", latency);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            return "";
        }
    }
}
