package com.example.agentplatform.rag.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台内置全文关键词匹配与 BM25 分数评估器
 */
@Component
public class KeywordRetriever {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-zA-Z0-9_]+");

    /**
     * 计算检索 Query 与分段正文的关键词匹配分 (0.0 ~ 1.0)
     */
    public double computeScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0.0;
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT).trim();
        String lowerContent = content.toLowerCase(Locale.ROOT);

        // 1. 完全包含 Query 词条（精准命中）直接获得最高保底分
        if (lowerContent.contains(lowerQuery)) {
            return 1.0;
        }

        List<String> queryTokens = tokenize(lowerQuery);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        // 2. 统计 Token 出现频次与命中占比
        int matchCount = 0;
        int totalHits = 0;
        Set<String> matchedTokens = new HashSet<>();

        for (String token : queryTokens) {
            if (token.length() < 1) continue;
            int count = countOccurrences(lowerContent, token);
            if (count > 0) {
                matchCount++;
                totalHits += Math.min(count, 5); // 截断避免单个词刷分
                matchedTokens.add(token);
            }
        }

        if (matchedTokens.isEmpty()) {
            return 0.0;
        }

        // 3. 覆盖率得分 (0.0 ~ 0.7) + 词频密度得分 (0.0 ~ 0.3)
        double coverageRatio = (double) matchedTokens.size() / queryTokens.size();
        double densityScore = Math.min(0.3, totalHits * 0.05);

        return Math.min(1.0, (coverageRatio * 0.7) + densityScore);
    }

    /**
     * 分词提取（支持中英文混合）
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() == 1 && !Character.isLetterOrDigit(token.charAt(0))) {
                continue;
            }
            tokens.add(token);

            // 中文字符串超过 2 字，额外提取 2-gram 增加召回率
            if (token.length() > 2 && containsHan(token)) {
                for (int i = 0; i < token.length() - 1; i++) {
                    tokens.add(token.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private boolean containsHan(String s) {
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
