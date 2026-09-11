package com.example.agentplatform.security.guardrail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule-based heuristic detector for LLM Prompt Injections, Jailbreaks, and System Role Hijacking.
 */
public class PromptInjectionDetector {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Direct instruction overrides
            Pattern.compile("(?i)(?:ignore|disregard|forget|override)\\s+(?:all\\s+)?(?:previous|prior|above|system)\\s+(?:instructions|prompts|rules|commands)"),
            Pattern.compile("(?i)you\\s+(?:are\\s+(?:now\\s+)?|will\\s+(?:now\\s+)?|must\\s+(?:now\\s+)?)?(?:in\\s+)?(?:developer\\s+mode|dan\\s+mode|jailbreak|unrestricted|an\\s+unfiltered)"),
            Pattern.compile("(?i)\\b(?:developer\\s+mode|dan\\s+mode|jailbreak)\\b"),
            Pattern.compile("(?i)do\\s+anything\\s+now"),
            // System prompt extraction
            Pattern.compile("(?i)(?:print|output|show|reveal|display|leak|repeat)\\s+(?:your\\s+)?(?:system\\s+prompt|initial\\s+instructions|system\\s+message|hidden\\s+prompt)"),
            // Delimiter injection / role spoofing
            Pattern.compile("(?i)<\\|im_start\\|>system"),
            Pattern.compile("(?i)\\b(?:system|system_prompt):\\s*(?:you\\s+are|ignore)"),
            Pattern.compile("(?i)\\[SYSTEM_OVERRIDE\\]"),
            // Simulated / roleplay jailbreaks
            Pattern.compile("(?i)pretend\\s+(?:you\\s+have\\s+no\\s+rules|there\\s+are\\s+no\\s+restrictions|safety\\s+is\\s+off)")
    );

    public record DetectionResult(boolean detected, String matchedPattern) {}

    public static DetectionResult check(String text) {
        if (text == null || text.isBlank()) {
            return new DetectionResult(false, null);
        }
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(text).find()) {
                return new DetectionResult(true, p.pattern());
            }
        }
        return new DetectionResult(false, null);
    }
}
