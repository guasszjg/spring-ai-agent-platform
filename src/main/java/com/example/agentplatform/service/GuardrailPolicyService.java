package com.example.agentplatform.service;

import com.example.agentplatform.model.GuardrailPolicy;
import com.example.agentplatform.repository.GuardrailPolicyRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.event.PolicyChangedEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class GuardrailPolicyService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPolicyService.class);
    private static final Pattern HOURS_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)-([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GuardrailPolicyRepository policyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Cache<String, GuardrailPolicy> policyCache;

    public GuardrailPolicyService(GuardrailPolicyRepository policyRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.policyRepository = policyRepository;
        this.eventPublisher = eventPublisher;
        this.policyCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(2000)
                .build();
    }

    public GuardrailPolicy getEffectivePolicy(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return getGlobalPolicy();
        }
        return policyCache.get(ownerId, id -> policyRepository.findById(id)
                .or(() -> policyRepository.findById("GLOBAL"))
                .orElseGet(() -> defaultPolicy(id)));
    }

    public GuardrailPolicy getGlobalPolicy() {
        return policyCache.get("GLOBAL", id -> policyRepository.findById("GLOBAL")
                .orElseGet(() -> defaultPolicy("GLOBAL")));
    }

    @EventListener
    public void onPolicyChanged(PolicyChangedEvent event) {
        if (event == null || event.ownerId() == null) {
            policyCache.invalidateAll();
            return;
        }
        policyCache.invalidate(event.ownerId());
        if ("GLOBAL".equalsIgnoreCase(event.ownerId())) {
            policyCache.invalidateAll();
        }
        log.info("Guardrail policy cache invalidated for owner: {}", event.ownerId());
    }

    public boolean isWithinAllowedHours(GuardrailPolicy policy) {
        if (policy == null) return true;
        String hours = policy.getAllowedHours();
        if (hours == null || hours.isBlank()) {
            return true;
        }
        hours = hours.trim();
        if (!HOURS_PATTERN.matcher(hours).matches()) {
            return true; // 不规范则默认放行
        }
        try {
            String[] parts = hours.split("-");
            LocalTime start = LocalTime.parse(parts[0], TIME_FMT);
            LocalTime end = LocalTime.parse(parts[1], TIME_FMT);
            LocalTime now = LocalTime.now();
            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                // 跨午夜情况，如 22:00-06:00
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("Failed to parse allowedHours: {}", hours);
            return true;
        }
    }

    public void validatePolicy(GuardrailPolicy policy) {
        if (policy == null) return;
        if (policy.getAllowedHours() != null && !policy.getAllowedHours().isBlank()) {
            String h = policy.getAllowedHours().trim();
            if (!HOURS_PATTERN.matcher(h).matches()) {
                throw new IllegalArgumentException("工作时间段格式不正确，应为 HH:mm-HH:mm，例如 08:00-22:00");
            }
        }
        if (policy.getDefaultRpm() != null && policy.getDefaultRpm() <= 0) {
            throw new IllegalArgumentException("默认限流 RPM 必须大于 0");
        }
        if (policy.getDefaultDailyTokens() != null && policy.getDefaultDailyTokens() <= 0) {
            throw new IllegalArgumentException("每日 Token 配额必须大于 0");
        }
    }

    @Transactional
    public GuardrailPolicy savePolicy(GuardrailPolicy incoming, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("只读观察员无权修改护栏策略");
        }
        final String effectiveOwnerId = (incoming.getOwnerId() != null && actor.isSuperAdmin())
                ? incoming.getOwnerId()
                : (actor.isSuperAdmin() ? "GLOBAL" : actor.getUserId());
        validatePolicy(incoming);

        GuardrailPolicy policy = policyRepository.findById(effectiveOwnerId).orElseGet(() -> {
            GuardrailPolicy p = new GuardrailPolicy();
            p.setOwnerId(effectiveOwnerId);
            return p;
        });

        policy.setClientPolicy(incoming.getClientPolicy());
        policy.setDefaultRpm(incoming.getDefaultRpm());
        policy.setDefaultDailyTokens(incoming.getDefaultDailyTokens());
        policy.setMaxInputChars(incoming.getMaxInputChars());
        policy.setMaxHistoryTurns(incoming.getMaxHistoryTurns());
        policy.setPiiMask(incoming.getPiiMask());
        policy.setSensitiveWords(incoming.getSensitiveWords());
        policy.setSensitiveAction(incoming.getSensitiveAction());
        policy.setPromptInjection(incoming.getPromptInjection());
        policy.setOutputGuard(incoming.getOutputGuard());
        policy.setAllowedHours(incoming.getAllowedHours());
        policy.setKillSwitch(incoming.getKillSwitch());

        GuardrailPolicy saved = policyRepository.save(policy);
        eventPublisher.publishEvent(new PolicyChangedEvent(effectiveOwnerId));
        return saved;
    }

    private GuardrailPolicy defaultPolicy(String ownerId) {
        GuardrailPolicy p = new GuardrailPolicy();
        p.setOwnerId(ownerId);
        p.setClientPolicy("OFF");
        p.setDefaultRpm(120);
        p.setMaxInputChars(8000);
        p.setMaxHistoryTurns(30);
        p.setPiiMask(false);
        p.setOutputGuard(true);
        p.setKillSwitch(false);
        p.setSensitiveAction("BLOCK");
        p.setPromptInjection("LOG");
        return p;
    }

    public Cache<String, GuardrailPolicy> getPolicyCache() {
        return policyCache;
    }
}
