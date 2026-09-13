package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.SpringAiKnowledgeBaseProvider;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Spring AI 原生自研物理引擎适配器
 * 实现 RagEngineProvider 契约，接管本地索引管理与检索召回执行
 */
@Component
public class SpringAiRagEngineAdapter implements RagEngineProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRagEngineAdapter.class);

    private final SpringAiKnowledgeBaseProvider springAiProvider;

    public SpringAiRagEngineAdapter(SpringAiKnowledgeBaseProvider springAiProvider) {
        this.springAiProvider = springAiProvider;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.SPRING_AI;
    }

    @Override
    public EngineCapabilities getCapabilities() {
        return EngineCapabilities.springAiDefaults();
    }

    @Override
    public EngineIndexHandle createOrBindIndex(IndexBuildSpec spec) {
        log.info("Spring AI 引擎适配器初始化本地物理索引: kbId={}, name={}", spec.knowledgeBaseId(), spec.knowledgeBaseName());
        DifyDatasetDto dataset = springAiProvider.createDataset(
                spec.knowledgeBaseName() != null ? spec.knowledgeBaseName() : spec.knowledgeBaseId(),
                spec.description(),
                "high_quality",
                "only_me",
                spec.embeddingModel(),
                spec.embeddingProvider(),
                spec.searchMethod(),
                spec.topK(),
                spec.rerankEnabled(),
                spec.rerankMode(),
                spec.rerankModel(),
                spec.rerankModelProvider(),
                spec.vectorWeight(),
                spec.keywordWeight()
        );
        return EngineIndexHandle.of(dataset.getId(), EngineType.SPRING_AI, "READY");
    }

    @Override
    public void deleteIndex(String handleId) {
        if (handleId == null || handleId.isBlank()) {
            return;
        }
        log.info("Spring AI 引擎适配器删除本地物理索引: handleId={}", handleId);
        try {
            springAiProvider.deleteDataset(handleId);
        } catch (Exception e) {
            log.warn("Spring AI 物理索引清理异常: handleId={}, err={}", handleId, e.getMessage());
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String handleId, RetrievalRequest request) {
        if (handleId == null || handleId.isBlank() || request == null || request.query() == null || request.query().isBlank()) {
            return Collections.emptyList();
        }
        return springAiProvider.retrieve(handleId, request);
    }
}
