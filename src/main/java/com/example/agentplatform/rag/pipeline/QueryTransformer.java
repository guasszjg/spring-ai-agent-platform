package com.example.agentplatform.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 智能 Query 理解、改写与多查询扩展器 (Query Transformer & Multi-Query Expander)
 * 消除口语化噪音、补齐核心检索主干、支持多意图子查询扩展
 */
@Component
public class QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(QueryTransformer.class);

    private static final List<String> NOISE_PREFIXES = Arrays.asList(
            "请问一下", "请问", "我想了解一下", "我想问", "能不能告诉我", "我想知道",
            "麻烦帮我查一下", "麻烦问一下", "请教一下", "你知道", "帮我查查", "hello", "hi"
    );

    private static final List<String> NOISE_SUFFIXES = Arrays.asList(
            "谢谢", "麻烦了", "感激不尽", "拜托了", "急急急", "有知道的吗", "求解答"
    );

    private final ChatModel chatModel;

    public QueryTransformer(@Autowired(required = false) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public record TransformResult(
            String originalQuery,
            String rewrittenQuery,
            List<String> expandedQueries,
            boolean isRewritten
    ) {}

    /**
     * 执行 Query 智能改写与扩展
     */
    public TransformResult transform(String rawQuery, boolean rewriteEnabled, boolean multiQueryEnabled) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new TransformResult("", "", List.of(), false);
        }
        String cleanQuery = rawQuery.trim();
        if (!rewriteEnabled && !multiQueryEnabled) {
            return new TransformResult(cleanQuery, cleanQuery, List.of(cleanQuery), false);
        }

        // 1. 语义降噪改写
        String rewritten = cleanQuery;
        boolean modified = false;
        for (String p : NOISE_PREFIXES) {
            if (rewritten.startsWith(p)) {
                rewritten = rewritten.substring(p.length()).trim();
                modified = true;
            }
        }
        for (String s : NOISE_SUFFIXES) {
            if (rewritten.endsWith(s)) {
                rewritten = rewritten.substring(0, rewritten.length() - s.length()).trim();
                modified = true;
            }
        }
        rewritten = rewritten.replaceAll("^[，。！？,.?!\\s]+|[，。！？,.?!\\s]+$", "");
        if (rewritten.isEmpty()) {
            rewritten = cleanQuery;
        }

        // 2. 多查询扩展 (Multi-Query Expansion)
        Set<String> expanded = new LinkedHashSet<>();
        expanded.add(rewritten);
        if (multiQueryEnabled) {
            // 衍生核心关键词形态
            String keywordQuery = extractCoreKeywordQuery(rewritten);
            if (!keywordQuery.equals(rewritten) && !keywordQuery.isBlank()) {
                expanded.add(keywordQuery);
            }
            // 衍生疑问语义形态
            if (!rewritten.endsWith("是什么") && !rewritten.endsWith("规则") && !rewritten.contains("怎么") && !rewritten.contains("如何")) {
                expanded.add(rewritten + " 说明与规范");
            }
        }

        log.debug("Query 改写与扩展结果: raw='{}' -> rewritten='{}', expandedCount={}", rawQuery, rewritten, expanded.size());
        return new TransformResult(cleanQuery, rewritten, new ArrayList<>(expanded), modified);
    }

    private String extractCoreKeywordQuery(String text) {
        return text.replaceAll("(是怎样的|是什么|怎么弄|如何处理|怎么做|怎么办|的流程|的要求)", "")
                .replaceAll("[吗呢吧呀啊？?！!]+", "")
                .trim();
    }
}
