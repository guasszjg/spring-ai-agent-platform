package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.rag.engine.EngineType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 知识来源隔离规则：自建知识库与 Dify 知识库分开管理、分开使用。
 * 一个智能体只能绑定同一种来源的知识库——两种引擎的相关度分数不可比，混合排序会让召回质量不可控。
 */
public final class KnowledgeSourcePolicy {

    private KnowledgeSourcePolicy() {
    }

    /** 知识库所属引擎；历史数据中缺失或非法的值按 Dify 处理（与旧版默认值一致）。 */
    public static EngineType engineOf(KnowledgeBase kb) {
        String provider = kb.getProvider();
        if (provider == null || provider.isBlank()) {
            return EngineType.DIFY;
        }
        try {
            return EngineType.valueOf(provider.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EngineType.DIFY;
        }
    }

    public static String sourceName(EngineType engine) {
        return engine == EngineType.SPRING_AI ? "自建知识库" : "Dify 知识库";
    }

    /**
     * 校验一组知识库属于同一种来源，否则抛出 IllegalArgumentException。
     *
     * @return 这组知识库的来源；集合为空时返回 null
     */
    public static EngineType requireSingleSource(Collection<KnowledgeBase> kbs) {
        Set<EngineType> engines = new LinkedHashSet<>();
        for (KnowledgeBase kb : kbs) {
            engines.add(engineOf(kb));
        }
        if (engines.size() > 1) {
            throw new IllegalArgumentException("一个智能体只能使用一种知识来源：自建知识库和 Dify 知识库不能同时绑定");
        }
        return engines.isEmpty() ? null : engines.iterator().next();
    }
}
