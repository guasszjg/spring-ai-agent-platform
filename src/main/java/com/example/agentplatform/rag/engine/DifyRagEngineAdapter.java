package com.example.agentplatform.rag.engine;

import com.example.agentplatform.rag.DifyKnowledgeBaseProvider;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Dify 外挂物理引擎适配器
 * 实现 RagEngineProvider 契约，将统一索引与检索动作翻译为 Dify 远程 API 调用
 */
@Component
public class DifyRagEngineAdapter implements RagEngineProvider {

    private static final Logger log = LoggerFactory.getLogger(DifyRagEngineAdapter.class);

    private final DifyKnowledgeBaseProvider difyProvider;

    public DifyRagEngineAdapter(DifyKnowledgeBaseProvider difyProvider) {
        this.difyProvider = difyProvider;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.DIFY;
    }

    @Override
    public EngineCapabilities getCapabilities() {
        return EngineCapabilities.difyDefaults();
    }

    @Override
    public EngineIndexHandle createOrBindIndex(IndexBuildSpec spec) {
        log.info("Dify 引擎适配器创建物理数据集: kbId={}, name={}", spec.knowledgeBaseId(), spec.knowledgeBaseName());
        DifyDatasetDto dataset = difyProvider.createDataset(
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
        if (dataset == null || dataset.getId() == null) {
            throw new IllegalStateException("Dify 数据集创建失败，未返回有效 ID");
        }
        return EngineIndexHandle.of(dataset.getId(), EngineType.DIFY, "READY");
    }

    @Override
    public void deleteIndex(String handleId) {
        if (handleId == null || handleId.isBlank()) {
            return;
        }
        log.info("Dify 引擎适配器删除物理数据集: handleId={}", handleId);
        try {
            difyProvider.deleteDataset(handleId);
        } catch (Exception e) {
            log.warn("Dify 数据集删除失败: handleId={}, err={}", handleId, e.getMessage());
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String handleId, RetrievalRequest request) {
        if (handleId == null || handleId.isBlank() || request == null || request.query() == null || request.query().isBlank()) {
            return Collections.emptyList();
        }
        return difyProvider.retrieve(handleId, request);
    }
}
