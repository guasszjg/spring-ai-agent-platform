package com.example.agentplatform.rag.engine;

import java.util.Collections;
import java.util.List;

/**
 * 三级引擎决议解释对象（系统默认 L1 -> 知识库绑定 L2 -> 调试覆盖 L3）
 */
public record EngineResolution(
        EngineType effectiveEngine,
        String source,                // L1_SYSTEM_DEFAULT, L2_KB_BINDING, L3_DEBUG_OVERRIDE
        List<ResolutionStep> chain,
        Integer indexVersionNo,
        String indexVersionId,
        boolean fallback
) {
    public record ResolutionStep(
            String level,             // L1, L2, L3
            String value,             // DIFY, SPRING_AI, or null
            boolean overridden
    ) {}

    public static EngineResolution direct(EngineType engine, String source) {
        return new EngineResolution(
                engine,
                source,
                List.of(new ResolutionStep("L2", engine.name(), false)),
                1,
                null,
                false
        );
    }

    public static EngineResolution resolved(EngineType effective, String source, List<ResolutionStep> chain,
                                           Integer versionNo, String versionId) {
        return new EngineResolution(
                effective,
                source,
                chain != null ? chain : Collections.emptyList(),
                versionNo,
                versionId,
                false
        );
    }
}
