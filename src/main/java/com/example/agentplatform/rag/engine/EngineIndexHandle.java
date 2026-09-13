package com.example.agentplatform.rag.engine;

import java.util.Collections;
import java.util.Map;

/**
 * 物理索引句柄（解耦底层厂商特定标识如 Dify datasetId 或自研 indexVersionId）
 */
public record EngineIndexHandle(
        String handleId,             // 句柄唯一标识（例如 Dify 数据集 ID，或本地自研版本 ID）
        EngineType engineType,       // 引擎类型
        String status,               // READY, BUILDING, FAILED 等
        Map<String, Object> properties // 引擎扩展属性
) {
    public static EngineIndexHandle of(String handleId, EngineType engineType, String status) {
        return new EngineIndexHandle(handleId, engineType, status, Collections.emptyMap());
    }

    public static EngineIndexHandle of(String handleId, EngineType engineType, String status, Map<String, Object> properties) {
        return new EngineIndexHandle(handleId, engineType, status, properties != null ? properties : Collections.emptyMap());
    }
}
