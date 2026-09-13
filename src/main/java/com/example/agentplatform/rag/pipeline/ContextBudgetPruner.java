package com.example.agentplatform.rag.pipeline;

import com.example.agentplatform.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文 Token 预算裁剪与动态压缩器 (Context Budget Pruner)
 * 彻底废除原有硬编码的 "12块 × 1500字符" 粗暴截断 (修复 F4 缺陷)，按大模型实际上下文窗口精准装填
 */
@Component
public class ContextBudgetPruner {

    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 3000;

    public record PruneResult(
            List<RetrievedChunk> prunedChunks,
            long totalTokens,
            int keptCount,
            int droppedCount,
            boolean isBudgetExhausted
    ) {}

    /**
     * 按 Token 预算对命中切片进行动态装填与裁剪
     *
     * @param chunks           按相关度降序排列的候选切片
     * @param maxContextTokens 最大允许注入的 Token 上限 (如 3000)
     * @return 裁剪装填结果
     */
    public PruneResult prune(List<RetrievedChunk> chunks, Integer maxContextTokens) {
        if (chunks == null || chunks.isEmpty()) {
            return new PruneResult(List.of(), 0L, 0, 0, false);
        }
        int budget = (maxContextTokens != null && maxContextTokens > 0) ? maxContextTokens : DEFAULT_MAX_CONTEXT_TOKENS;

        List<RetrievedChunk> kept = new ArrayList<>();
        long currentTokens = 0L;
        int dropped = 0;
        boolean exhausted = false;

        for (RetrievedChunk chunk : chunks) {
            long chunkTokens = chunk.tokenCount() != null && chunk.tokenCount() > 0
                    ? chunk.tokenCount()
                    : Math.max(1L, (long) (chunk.content() != null ? chunk.content().length() * 1.3 : 0));

            if (currentTokens + chunkTokens <= budget) {
                kept.add(chunk);
                currentTokens += chunkTokens;
            } else {
                // 若当前尚有剩余预算 (≥ 100 Token)，且这是第一个超预算的高分块，进行句尾优雅截断装填
                long remaining = budget - currentTokens;
                if (remaining >= 100 && kept.isEmpty()) {
                    String partial = truncateAtSentence(chunk.content(), (int) (remaining / 1.3));
                    if (!partial.isBlank()) {
                        RetrievedChunk partialChunk = RetrievedChunk.builder()
                                .chunkId(chunk.chunkId())
                                .documentId(chunk.documentId())
                                .sourceName(chunk.sourceName())
                                .content(partial + " ...[按Token预算截断]")
                                .score(chunk.score())
                                .vectorScore(chunk.vectorScore())
                                .keywordScore(chunk.keywordScore())
                                .rerankScore(chunk.rerankScore())
                                .tokenCount((int) remaining)
                                .metadata(chunk.metadata())
                                .build();
                        kept.add(partialChunk);
                        currentTokens += remaining;
                    }
                }
                dropped++;
                exhausted = true;
            }
        }

        return new PruneResult(kept, currentTokens, kept.size(), dropped, exhausted);
    }

    private String truncateAtSentence(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int limit = Math.max(50, maxChars);
        String sub = text.substring(0, Math.min(text.length(), limit));
        int lastPeriod = Math.max(sub.lastIndexOf('。'), Math.max(sub.lastIndexOf('\n'), sub.lastIndexOf('.')));
        if (lastPeriod > limit / 2) {
            return sub.substring(0, lastPeriod + 1);
        }
        return sub;
    }
}
