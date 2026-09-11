package com.example.agentplatform.security.guardrail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privacy Identifiable Information (PII) Masker for China & International standard formats.
 * Masks: Mobile Phone, Citizen ID Card, Bank Card / Credit Card, Email address.
 */
public class PiiMasker {

    // Chinese Mainland Mobile Phone (11 digits): 1[3-9]\d{9}
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?86)?(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    // Chinese Resident ID Card (18 digits)
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(\\d{3}[0-9Xx])(?!\\d)");

    // Bank Card (16 to 19 digits)
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)([3-6]\\d{5})\\d{6,9}(\\d{4})(?!\\d)");

    // Email Address
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9._%+-]{1,2})[a-zA-Z0-9._%+-]*(@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\b");

    public static String mask(String text) {
        if (text == null || text.isBlank()) return text;

        String result = text;
        // 1. Mask Phone: 13800138000 -> 138****8000
        result = PHONE_PATTERN.matcher(result).replaceAll("$1****$2");

        // 2. Mask ID Card: 110101199003072345 -> 110101********2345
        result = ID_CARD_PATTERN.matcher(result).replaceAll("$1********$2");

        // 3. Mask Bank Card: 6222021234567890123 -> 622202******0123
        Matcher bankMatcher = BANK_CARD_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (bankMatcher.find()) {
            String prefix = bankMatcher.group(1);
            String suffix = bankMatcher.group(2);
            bankMatcher.appendReplacement(sb, prefix + "******" + suffix);
        }
        bankMatcher.appendTail(sb);
        result = sb.toString();

        // 4. Mask Email: user@example.com -> u***@example.com
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1***$2");

        return result;
    }
}
