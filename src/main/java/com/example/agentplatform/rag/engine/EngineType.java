package com.example.agentplatform.rag.engine;

/**
 * RAG 物理引擎类型标识
 */
public enum EngineType {
    DIFY("Dify 外挂引擎"),
    SPRING_AI("Spring AI 自研引擎");

    private final String description;

    EngineType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
