package com.example.agentplatform.model;

import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.engine.EngineCapabilities;
import com.example.agentplatform.rag.engine.EngineIndexHandle;
import com.example.agentplatform.rag.engine.EngineResolution;
import com.example.agentplatform.rag.engine.EngineType;
import com.example.agentplatform.rag.engine.IndexBuildSpec;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.engine.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDualEngineModelTest {

    @Test
    void knowledgeIndexVersion_prePersist_setsSensibleDefaults() {
        KnowledgeIndexVersion version = new KnowledgeIndexVersion();
        version.setKnowledgeBaseId("kb-100");
        version.setVersionNo(1);
        version.prePersist();

        assertThat(version.getId()).startsWith("kiv-");
        assertThat(version.getStatus()).isEqualTo("DRAFT");
        assertThat(version.getEngineType()).isEqualTo("DIFY");
        assertThat(version.getDimension()).isEqualTo(1024);
        assertThat(version.getDistanceMetric()).isEqualTo("COSINE");
        assertThat(version.getCreatedAt()).isNotNull();
        assertThat(version.getUpdatedAt()).isNotNull();
    }

    @Test
    void knowledgeSourceRevision_prePersist_setsDefaults() {
        KnowledgeSourceRevision rev = new KnowledgeSourceRevision();
        rev.setKnowledgeBaseId("kb-100");
        rev.setDocumentId("doc-200");
        rev.setFileName("manual.pdf");
        rev.setSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        rev.setObjectKey("kb-100/doc-200/manual.pdf");
        rev.prePersist();

        assertThat(rev.getId()).startsWith("ksr-");
        assertThat(rev.getStorageType()).isEqualTo("LOCAL");
        assertThat(rev.getSizeBytes()).isEqualTo(0L);
        assertThat(rev.getCreatedAt()).isNotNull();
    }

    @Test
    void knowledgeIndexJob_prePersist_setsDefaults() {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setKnowledgeBaseId("kb-100");
        job.setIndexVersionId("kiv-100");
        job.prePersist();

        assertThat(job.getId()).startsWith("kij-");
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getJobStage()).isEqualTo("PARSE");
        assertThat(job.getAttempt()).isEqualTo(0);
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getCreatedAt()).isNotNull();
    }

    @Test
    void knowledgeBase_supportsNewDualEngineFields() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("测试知识库");
        kb.prePersist();

        assertThat(kb.getScoreThreshold()).isEqualTo(0.5);
        assertThat(kb.getSourceSnapshotNo()).isEqualTo(1);

        kb.setActiveIndexVersionId("kiv-v1");
        assertThat(kb.getActiveIndexVersionId()).isEqualTo("kiv-v1");
    }

    @Test
    void retrievedChunk_builderAndLegacyConstructor_compatCheck() {
        // Legacy 3-arg constructor
        RetrievedChunk legacy = new RetrievedChunk("内容", "文档.pdf", 0.85);
        assertThat(legacy.content()).isEqualTo("内容");
        assertThat(legacy.sourceName()).isEqualTo("文档.pdf");
        assertThat(legacy.score()).isEqualTo(0.85);
        assertThat(legacy.matchType()).isEqualTo("VECTOR");

        // Rich builder constructor
        RetrievedChunk rich = RetrievedChunk.builder()
                .chunkId("chk-1")
                .documentId("doc-1")
                .parentChunkId("p-chk-1")
                .sourceName("架构规范.md")
                .pageNumber(2)
                .content("向量检索与全文检索双路召回")
                .rawContent("向量检索与全文检索双路召回...")
                .score(0.92)
                .vectorScore(0.95)
                .keywordScore(0.88)
                .rerankScore(0.94)
                .matchType("HYBRID")
                .tokenCount(42)
                .metadata(Map.of("category", "tech"))
                .build();

        assertThat(rich.chunkId()).isEqualTo("chk-1");
        assertThat(rich.pageNo()).isEqualTo(2);
        assertThat(rich.fusedScore()).isEqualTo(0.92);
        assertThat(rich.tokenCount()).isEqualTo(42);
        assertThat(rich.metadata()).containsEntry("category", "tech");
    }

    @Test
    void engineContracts_workTogetherCleanly() {
        IndexBuildSpec spec = IndexBuildSpec.builder()
                .knowledgeBaseId("kb-1")
                .knowledgeBaseName("金融手册")
                .engineType(EngineType.SPRING_AI)
                .dimensions(1024)
                .build();

        assertThat(spec.engineType()).isEqualTo(EngineType.SPRING_AI);

        EngineIndexHandle handle = EngineIndexHandle.of("handle-1", EngineType.SPRING_AI, "READY");
        assertThat(handle.engineType()).isEqualTo(EngineType.SPRING_AI);

        EngineCapabilities caps = EngineCapabilities.springAiDefaults();
        assertThat(caps.supportsMultimodal()).isTrue();

        EngineResolution resolution = EngineResolution.direct(EngineType.SPRING_AI, "L2_KB_BINDING");
        assertThat(resolution.effectiveEngine()).isEqualTo(EngineType.SPRING_AI);

        RetrievalResult result = RetrievalResult.of("query", java.util.List.of(), resolution, 15L);
        assertThat(result.latencyMs()).isEqualTo(15L);
        assertThat(result.engineResolution().effectiveEngine()).isEqualTo(EngineType.SPRING_AI);
    }
}
