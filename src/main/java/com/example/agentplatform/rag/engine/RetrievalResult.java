package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.RetrievedChunk;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统一检索响应结果
 */
public record RetrievalResult(
        String query,
        List<RetrievedChunk> chunks,
        EngineResolution engineResolution,
        long latencyMs,
        Map<String, Object> metrics
) {
    public static RetrievalResult of(String query, List<RetrievedChunk> chunks, EngineResolution resolution, long latencyMs) {
        return new RetrievalResult(query, chunks, resolution, latencyMs, Collections.emptyMap());
    }

    public static RetrievalResult of(String query, List<RetrievedChunk> chunks, EngineResolution resolution, long latencyMs, Map<String, Object> metrics) {
        return new RetrievalResult(query, chunks, resolution, latencyMs, metrics != null ? metrics : Collections.emptyMap());
    }
}
