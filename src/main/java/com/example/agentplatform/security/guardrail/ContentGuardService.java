package com.example.agentplatform.security.guardrail;

import com.example.agentplatform.model.GuardrailPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContentGuardService {

    private static final Logger log = LoggerFactory.getLogger(ContentGuardService.class);

    private final Map<String, SensitiveWordMatcher> matcherCache = new ConcurrentHashMap<>();

    public InputGuardResult inspectInput(GuardrailPolicy policy, String message) {
        if (message == null || message.isBlank()) {
            return InputGuardResult.block("bad_request", "请求参数 message 不能为空", null);
        }

        if (policy == null) {
            return InputGuardResult.allow(message, null, null);
        }

        // 1. Max Input Characters Check
        if (policy.getMaxInputChars() != null && message.length() > policy.getMaxInputChars()) {
            return InputGuardResult.block("input_too_long", "输入字符数 " + message.length() + " 超过最大限制 " + policy.getMaxInputChars(), null);
        }

        String currentText = message;

        // 2. PII Masking
        if (Boolean.TRUE.equals(policy.getPiiMask())) {
            currentText = PiiMasker.mask(currentText);
        }

        // 3. Prompt Injection Detection
        String promptAction = policy.getPromptInjection() != null ? policy.getPromptInjection().toUpperCase() : "LOG";
        if (!"OFF".equalsIgnoreCase(promptAction)) {
            PromptInjectionDetector.DetectionResult injection = PromptInjectionDetector.check(currentText);
            if (injection.detected()) {
                if ("BLOCK".equalsIgnoreCase(promptAction)) {
                    log.warn("Prompt injection blocked: pattern={}", injection.matchedPattern());
                    return InputGuardResult.block("prompt_injection", "输入包含违规的提示词越狱或系统指令劫持", injection.matchedPattern());
                } else if ("LOG".equalsIgnoreCase(promptAction)) {
                    log.info("Prompt injection logged: pattern={}", injection.matchedPattern());
                    return InputGuardResult.allow(currentText, "PROMPT_INJECTION", injection.matchedPattern());
                }
            }
        }

        // 4. Sensitive Word Matcher (AC Automaton)
        List<String> words = policy.getSensitiveWords();
        if (words != null && !words.isEmpty()) {
            SensitiveWordMatcher matcher = getOrCreateMatcher(words);
            Optional<SensitiveWordMatcher.MatchResult> match = matcher.findFirst(currentText);
            if (match.isPresent()) {
                String term = match.get().getWord();
                String action = policy.getSensitiveAction() != null ? policy.getSensitiveAction().toUpperCase() : "BLOCK";
                if ("BLOCK".equals(action)) {
                    return InputGuardResult.block("sensitive_content", "输入包含敏感或违规内容: " + term, term);
                } else if ("MASK".equals(action)) {
                    currentText = matcher.mask(currentText, '*');
                    return InputGuardResult.allow(currentText, null, term);
                } else if ("LOG".equals(action)) {
                    return InputGuardResult.allow(currentText, "SENSITIVE_CONTENT_LOG", term);
                }
            }
        }

        return InputGuardResult.allow(currentText, null, null);
    }

    public OutputGuardResult inspectOutput(GuardrailPolicy policy, String reply) {
        if (reply == null || reply.isBlank() || policy == null) {
            return OutputGuardResult.allow(reply);
        }

        if (!Boolean.TRUE.equals(policy.getOutputGuard())) {
            return OutputGuardResult.allow(reply);
        }

        String currentText = reply;

        // PII Masking on Output
        if (Boolean.TRUE.equals(policy.getPiiMask())) {
            currentText = PiiMasker.mask(currentText);
        }

        // Sensitive Word Check on Output
        List<String> words = policy.getSensitiveWords();
        if (words != null && !words.isEmpty()) {
            SensitiveWordMatcher matcher = getOrCreateMatcher(words);
            Optional<SensitiveWordMatcher.MatchResult> match = matcher.findFirst(currentText);
            if (match.isPresent()) {
                String term = match.get().getWord();
                String action = policy.getSensitiveAction() != null ? policy.getSensitiveAction().toUpperCase() : "BLOCK";
                if ("BLOCK".equals(action)) {
                    return OutputGuardResult.block("sensitive_output", "智能体输出包含敏感或违规内容", term);
                } else if ("MASK".equals(action)) {
                    currentText = matcher.mask(currentText, '*');
                    return OutputGuardResult.allow(currentText);
                }
            }
        }

        return OutputGuardResult.allow(currentText);
    }

    public StreamingSlidingWindowGuard createStreamingGuard(GuardrailPolicy policy) {
        if (policy == null || !Boolean.TRUE.equals(policy.getOutputGuard())) {
            return new StreamingSlidingWindowGuard(null, false, false, 32);
        }
        List<String> words = policy.getSensitiveWords();
        if (words == null || words.isEmpty()) {
            return new StreamingSlidingWindowGuard(null, false, false, 32);
        }
        SensitiveWordMatcher matcher = getOrCreateMatcher(words);
        boolean blockOnViolation = !"MASK".equalsIgnoreCase(policy.getSensitiveAction());
        return new StreamingSlidingWindowGuard(matcher, true, blockOnViolation, 32);
    }

    private SensitiveWordMatcher getOrCreateMatcher(List<String> words) {
        String key = String.join(",", words);
        return matcherCache.computeIfAbsent(key, k -> new SensitiveWordMatcher(words));
    }
}
