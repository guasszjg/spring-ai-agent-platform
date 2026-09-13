package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.RetrievedChunk;

import java.util.List;
import java.util.Map;

/**
 * 影子流量与双引擎对比评测结果 DTO (Shadow Traffic & A/B Evaluation Result)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 6.3 节规范
 */
public record ShadowEvaluationResult(
        String query,
        String primaryEngine,
        String secondaryEngine,
        List<RetrievedChunk> primaryChunks,
        List<RetrievedChunk> secondaryChunks,
        double overlapRatio,           // Jaccard 召回内容重叠率 (0.0 ~ 1.0)
        int primaryOnlyCount,          // 自研引擎独有召回切片数
        int secondaryOnlyCount,        // 对标引擎独有召回切片数
        long primaryLatencyMs,         // 自研引擎端到端耗时 (ms)
        long secondaryLatencyMs,       // 对标引擎端到端耗时 (ms)
        long latencyDiffMs,            // 耗时差 (负数表示自研更快)
        int primaryTokens,             // 自研引擎上下文 Token 总量
        int secondaryTokens,           // 对标引擎上下文 Token 总量
        Map<String, Object> metrics
) {
    public static ShadowEvaluationResult of(
            String query,
            String primaryEngine,
            String secondaryEngine,
            List<RetrievedChunk> primaryChunks,
            List<RetrievedChunk> secondaryChunks,
            long primaryLatencyMs,
            long secondaryLatencyMs,
            Map<String, Object> metrics
    ) {
        int pCount = primaryChunks != null ? primaryChunks.size() : 0;
        int sCount = secondaryChunks != null ? secondaryChunks.size() : 0;

        // 计算 Jaccard 字符与内容相似度交集
        int intersectionCount = 0;
        if (primaryChunks != null && secondaryChunks != null) {
            for (RetrievedChunk pc : primaryChunks) {
                String pText = pc.content() != null ? pc.content().trim() : "";
                for (RetrievedChunk sc : secondaryChunks) {
                    String sText = sc.content() != null ? sc.content().trim() : "";
                    if (pText.contains(sText) || sText.contains(pText) || (pc.chunkId() != null && pc.chunkId().equals(sc.chunkId()))) {
                        intersectionCount++;
                        break;
                    }
                }
            }
        }

        int unionCount = pCount + sCount - intersectionCount;
        double overlap = unionCount > 0 ? Math.round(((double) intersectionCount / unionCount) * 100.0) / 100.0 : 0.0;
        int pOnly = Math.max(0, pCount - intersectionCount);
        int sOnly = Math.max(0, sCount - intersectionCount);

        int pTokens = primaryChunks != null ? primaryChunks.stream().mapToInt(c -> c.tokenCount() != null ? c.tokenCount() : 0).sum() : 0;
        int sTokens = secondaryChunks != null ? secondaryChunks.stream().mapToInt(c -> c.tokenCount() != null ? c.tokenCount() : 0).sum() : 0;

        return new ShadowEvaluationResult(
                query,
                primaryEngine,
                secondaryEngine,
                primaryChunks != null ? primaryChunks : List.of(),
                secondaryChunks != null ? secondaryChunks : List.of(),
                overlap,
                pOnly,
                sOnly,
                primaryLatencyMs,
                secondaryLatencyMs,
                primaryLatencyMs - secondaryLatencyMs,
                pTokens,
                sTokens,
                metrics != null ? metrics : Map.of()
        );
    }
}
