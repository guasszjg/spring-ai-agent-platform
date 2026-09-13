package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocumentChunk;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.pipeline.ContextBudgetPruner;
import com.example.agentplatform.rag.pipeline.DocumentChunker;
import com.example.agentplatform.rag.pipeline.KeywordRetriever;
import com.example.agentplatform.rag.pipeline.LocalEmbeddingService;
import com.example.agentplatform.rag.pipeline.QueryTransformer;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentChunkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spring AI 原生自研 RAG 引擎 Provider 实现
 * P1 基线：全自主可控的本地切片、1024 维向量化与双路混合检索
 * P2 增强：高级父子分块 (Parent-Child Chunking)、Query 智能改写与 Token 预算裁剪
 */
@Service
public class SpringAiKnowledgeBaseProvider implements KnowledgeBaseProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiKnowledgeBaseProvider.class);

    private final KnowledgeDocumentChunkRepository chunkRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentChunker documentChunker;
    private final LocalEmbeddingService embeddingService;
    private final KeywordRetriever keywordRetriever;
    private final QueryTransformer queryTransformer;
    private final ContextBudgetPruner budgetPruner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiKnowledgeBaseProvider(KnowledgeDocumentChunkRepository chunkRepository,
                                         KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentChunker documentChunker,
                                         LocalEmbeddingService embeddingService,
                                         KeywordRetriever keywordRetriever,
                                         QueryTransformer queryTransformer,
                                         ContextBudgetPruner budgetPruner) {
        this.chunkRepository = chunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.keywordRetriever = keywordRetriever;
        this.queryTransformer = queryTransformer;
        this.budgetPruner = budgetPruner;
    }

    @Override
    public String getProviderType() {
        return "SPRING_AI";
    }

    @Override
    public String getBaseUrl() {
        return "local://spring-ai-rag-engine";
    }

    @Override
    public DifyDatasetDto createDataset(String name, String description, String indexingTechnique, String permission,
                                         String embeddingModel, String embeddingProvider, String searchMethod,
                                         Integer topK, Boolean rerankEnabled, String rerankMode, String rerankModel,
                                         String rerankModelProvider, Double vectorWeight, Double keywordWeight) {
        String handleId = "spring_ai_kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        DifyDatasetDto dto = new DifyDatasetDto();
        dto.setId(handleId);
        dto.setName(name);
        dto.setDescription(description);
        dto.setIndexingTechnique(indexingTechnique != null ? indexingTechnique : "high_quality");
        dto.setPermission(permission != null ? permission : "only_me");
        dto.setEmbeddingModel(embeddingModel != null ? embeddingModel : "spring-ai-native-1024");
        dto.setEmbeddingModelProvider(embeddingProvider != null ? embeddingProvider : "spring_ai");
        log.info("创建 Spring AI 自研知识库数据集句柄: handleId={}, name={}", handleId, name);
        return dto;
    }

    @Override
    public void updateDataset(String externalDatasetId, String name, String description, String searchMethod,
                              Integer topK, Boolean rerankEnabled, String rerankMode, String rerankModel,
                              String rerankModelProvider, Double vectorWeight, Double keywordWeight) {
        log.info("更新 Spring AI 自研知识库配置: handleId={}, name={}, searchMethod={}", externalDatasetId, name, searchMethod);
    }

    @Override
    @Transactional
    public void deleteDataset(String externalDatasetId) {
        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId != null) {
            chunkRepository.deleteByKnowledgeBaseId(kbId);
            log.info("清理 Spring AI 自研知识库分段切片: kbId={}", kbId);
        }
    }

    @Override
    public List<DifyDatasetDto> listExternalDatasets() {
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId == null) {
            kbId = externalDatasetId;
        }

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.txt";
        String textContent = extractText(file);
        long totalTokens = documentChunker.estimateTokens(textContent);

        // P2 高级父子切片管线 (Parent-Child Chunking)
        List<DocumentChunker.ParentChildPiece> pieces = documentChunker.splitParentChild(textContent);
        if (pieces.isEmpty() && !textContent.isBlank()) {
            pieces = List.of(new DocumentChunker.ParentChildPiece(0, textContent, null, textContent, textContent.length(), totalTokens, "STANDALONE"));
        }

        String docId = UUID.randomUUID().toString();
        List<KnowledgeDocumentChunk> entities = new ArrayList<>();

        for (DocumentChunker.ParentChildPiece piece : pieces) {
            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setKnowledgeBaseId(kbId);
            chunk.setDocumentId(docId);
            chunk.setChunkIndex(piece.index());
            chunk.setContent(piece.childContent());
            chunk.setParentChunkId(piece.parentChunkId());
            chunk.setParentContent(piece.parentContent());
            chunk.setChunkType(piece.chunkType());
            chunk.setCharacterCount(piece.charCount());
            chunk.setTokenCount(piece.tokenCount());
            chunk.setEnabled(true);

            // 生成 1024 维向量 (基于高精度子块向量化)
            float[] vector = embeddingService.embed(piece.childContent(), 1024);
            chunk.setEmbedding(embeddingService.serializeVector(vector));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sourceName", fileName);
            meta.put("chunkIndex", piece.index());
            meta.put("charCount", piece.charCount());
            meta.put("chunkType", piece.chunkType());
            if (piece.parentChunkId() != null) {
                meta.put("parentChunkId", piece.parentChunkId());
            }
            try {
                chunk.setMetadataJson(objectMapper.writeValueAsString(meta));
            } catch (Exception e) {
                chunk.setMetadataJson("{}");
            }

            entities.add(chunk);
        }

        chunkRepository.saveAll(entities);
        log.info("Spring AI 自研父子切片与向量入库完成: kbId={}, docId={}, fileName={}, chunkCount={}, tokens={}",
                kbId, docId, fileName, entities.size(), totalTokens);

        DifyDocumentDto dto = new DifyDocumentDto();
        dto.setId(docId);
        dto.setName(fileName);
        dto.setWordCount((long) textContent.length());
        dto.setTokens(totalTokens);
        dto.setIndexingStatus("completed");
        return dto;
    }

    @Override
    @Transactional
    public void deleteDocument(String externalDatasetId, String externalDocId) {
        if (externalDocId != null && !externalDocId.isBlank()) {
            chunkRepository.deleteByDocumentId(externalDocId);
            log.info("删除自研文档切片: docId={}", externalDocId);
        }
    }

    @Override
    public List<DifyDocumentDto> listDocuments(String externalDatasetId, int page, int limit) {
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public DifyDocumentDto syncFaqDocument(String externalDatasetId, String existingExternalDocId, String question,
                                          String answer, String category) {
        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId == null) {
            kbId = externalDatasetId;
        }

        String faqDocId = (existingExternalDocId != null && !existingExternalDocId.isBlank())
                ? existingExternalDocId
                : "faq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 先清理可能存在的旧分段
        chunkRepository.deleteByFaqId(faqDocId);

        String combinedText = "【业务问题】" + question + "\n【标准解答】" + answer;
        long tokens = documentChunker.estimateTokens(combinedText);

        KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
        chunk.setKnowledgeBaseId(kbId);
        chunk.setFaqId(faqDocId);
        chunk.setChunkIndex(0);
        chunk.setContent(combinedText);
        chunk.setParentContent(combinedText);
        chunk.setChunkType("STANDALONE");
        chunk.setCharacterCount(combinedText.length());
        chunk.setTokenCount(tokens);
        chunk.setEnabled(true);

        float[] vector = embeddingService.embed(combinedText, 1024);
        chunk.setEmbedding(embeddingService.serializeVector(vector));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceName", "FAQ: " + question);
        meta.put("category", category != null ? category : "通用问答");
        meta.put("docType", "faq");
        try {
            chunk.setMetadataJson(objectMapper.writeValueAsString(meta));
        } catch (Exception ignored) {}

        chunkRepository.save(chunk);

        DifyDocumentDto dto = new DifyDocumentDto();
        dto.setId(faqDocId);
        dto.setName(question);
        dto.setWordCount((long) combinedText.length());
        dto.setTokens(tokens);
        dto.setIndexingStatus("completed");
        return dto;
    }

    @Override
    @Transactional
    public void deleteFaqDocument(String externalDatasetId, String externalDocId) {
        if (externalDocId != null && !externalDocId.isBlank()) {
            chunkRepository.deleteByFaqId(externalDocId);
            log.info("删除自研 FAQ 切片: faqId={}", externalDocId);
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String externalDatasetId, String query) {
        return retrieve(externalDatasetId, query, 3, 0.3);
    }

    @Override
    public List<RetrievedChunk> retrieve(String externalDatasetId, String query, Integer topK, Double scoreThreshold) {
        RetrievalRequest req = RetrievalRequest.builder()
                .knowledgeBaseId(externalDatasetId)
                .query(query)
                .topK(topK != null ? topK : 3)
                .scoreThreshold(scoreThreshold != null ? scoreThreshold : 0.3)
                .searchMethod("hybrid_search")
                .rerankEnabled(true)
                .vectorWeight(0.7)
                .keywordWeight(0.3)
                .rewriteEnabled(false)
                .expandParent(true)
                .maxContextTokens(3000)
                .build();
        return retrieve(externalDatasetId, req);
    }

    @Override
    public List<RetrievedChunk> retrieve(String externalDatasetId, RetrievalRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return Collections.emptyList();
        }

        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId == null) {
            kbId = externalDatasetId;
        }

        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(kbId);
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Query 智能理解与改写 (P2 阶段)
        boolean rewriteEnabled = Boolean.TRUE.equals(request.rewriteEnabled());
        QueryTransformer.TransformResult transformResult = queryTransformer.transform(request.query(), rewriteEnabled, false);
        String effectiveQuery = transformResult.rewrittenQuery();

        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 3;
        double threshold = request.scoreThreshold() != null ? request.scoreThreshold() : 0.3;
        String searchMethod = request.searchMethod() != null ? request.searchMethod().toLowerCase() : "hybrid_search";
        boolean rerankEnabled = Boolean.TRUE.equals(request.rerankEnabled());
        double vectorWeight = request.vectorWeight() != null ? request.vectorWeight() : 0.7;
        double keywordWeight = request.keywordWeight() != null ? request.keywordWeight() : 0.3;

        // 计算 Query 向量 (基于优化后的 Query)
        float[] queryVec = embeddingService.embed(effectiveQuery, 1024);

        List<ScoredChunk> candidates = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            float[] chunkVec = embeddingService.deserializeVector(chunk.getEmbedding());
            double vectorScore = (chunkVec.length > 0) ? embeddingService.cosineSimilarity(queryVec, chunkVec) : 0.0;
            double keywordScore = keywordRetriever.computeScore(effectiveQuery, chunk.getContent());

            double fusedScore;
            if ("semantic_search".equals(searchMethod)) {
                fusedScore = vectorScore;
            } else if ("keyword_search".equals(searchMethod)) {
                fusedScore = keywordScore;
            } else {
                fusedScore = (vectorWeight * vectorScore) + (keywordWeight * keywordScore);
            }

            double rerankScore = fusedScore;
            if (rerankEnabled) {
                // 重排提权机制：完全包含问题短语或高密度关键词时加权
                if (chunk.getContent() != null && chunk.getContent().toLowerCase().contains(effectiveQuery.toLowerCase().trim())) {
                    rerankScore = Math.min(1.0, fusedScore * 1.25);
                } else if (keywordScore > 0.6) {
                    rerankScore = Math.min(1.0, fusedScore * 1.12);
                }
            }

            double finalScore = rerankEnabled ? rerankScore : fusedScore;
            if (finalScore >= threshold) {
                candidates.add(new ScoredChunk(chunk, finalScore, vectorScore, keywordScore, rerankScore));
            }
        }

        candidates.sort(Comparator.comparingDouble(ScoredChunk::finalScore).reversed());

        // 2. 父子切片回溯展开与跨段去重 (P2 阶段)
        boolean expandParent = request.expandParent() == null || Boolean.TRUE.equals(request.expandParent());
        List<RetrievedChunk> candidateResults = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();

        for (ScoredChunk sc : candidates) {
            if (candidateResults.size() >= topK * 2) {
                break; // 稍微多备选以备预算裁剪
            }

            KnowledgeDocumentChunk ch = sc.chunk();
            String sourceName = parseSourceName(ch);

            boolean parentExpanded = false;
            String finalContent = ch.getContent();
            long finalTokens = ch.getTokenCount() != null ? ch.getTokenCount() : 0L;

            if (expandParent && ch.getParentContent() != null && !ch.getParentContent().isBlank()) {
                String pId = ch.getParentChunkId() != null ? ch.getParentChunkId() : ("parent_" + ch.getId());
                if (seenParents.contains(pId)) {
                    // 同一父块已被更高分数的子切片命中，去重跳过，避免重复注入大段文本
                    continue;
                }
                seenParents.add(pId);
                finalContent = ch.getParentContent();
                finalTokens = documentChunker.estimateTokens(finalContent);
                parentExpanded = true;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("segmentIndex", ch.getChunkIndex() != null ? ch.getChunkIndex() + 1 : 1);
            metadata.put("characterCount", finalContent.length());
            metadata.put("parentExpanded", parentExpanded);
            metadata.put("chunkType", ch.getChunkType() != null ? ch.getChunkType() : "CHILD");
            if (parentExpanded) {
                metadata.put("originalChildContent", ch.getContent());
                metadata.put("parentChunkId", ch.getParentChunkId());
            }
            if (transformResult.isRewritten()) {
                metadata.put("originalQuery", transformResult.originalQuery());
                metadata.put("rewrittenQuery", transformResult.rewrittenQuery());
            }

            RetrievedChunk rc = RetrievedChunk.builder()
                    .chunkId(ch.getId())
                    .documentId(ch.getDocumentId())
                    .parentChunkId(ch.getParentChunkId())
                    .sourceName(sourceName)
                    .pageNumber(ch.getChunkIndex() != null ? ch.getChunkIndex() + 1 : 1)
                    .content(finalContent)
                    .rawContent(ch.getContent())
                    .score(round4(sc.finalScore()))
                    .vectorScore(round4(sc.vectorScore()))
                    .keywordScore(round4(sc.keywordScore()))
                    .rerankScore(round4(sc.rerankScore()))
                    .matchType(rerankEnabled ? "RERANK" : ("semantic_search".equals(searchMethod) ? "VECTOR" : "HYBRID"))
                    .tokenCount((int) finalTokens)
                    .metadata(metadata)
                    .build();
            candidateResults.add(rc);
        }

        // 截断到 topK
        List<RetrievedChunk> topKResults = candidateResults.size() > topK
                ? candidateResults.subList(0, topK)
                : candidateResults;

        // 3. 上下文 Token 预算裁剪与动态装填 (P2 阶段 - 修复 F4 缺陷)
        ContextBudgetPruner.PruneResult pruneResult = budgetPruner.prune(topKResults, request.maxContextTokens());
        return pruneResult.prunedChunks();
    }

    private String resolveKnowledgeBaseId(String externalDatasetId) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) {
            return null;
        }
        Optional<KnowledgeBase> byExt = knowledgeBaseRepository.findByExternalDatasetId(externalDatasetId);
        if (byExt.isPresent()) {
            return byExt.get().getId();
        }
        Optional<KnowledgeBase> byId = knowledgeBaseRepository.findById(externalDatasetId);
        return byId.map(KnowledgeBase::getId).orElse(externalDatasetId);
    }

    private String extractText(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("提取上传文件文本失败: {}", e.getMessage());
            return "";
        }
    }

    private String parseSourceName(KnowledgeDocumentChunk chunk) {
        if (chunk.getMetadataJson() != null && !chunk.getMetadataJson().isBlank()) {
            try {
                Map<String, Object> map = objectMapper.readValue(chunk.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
                Object name = map.get("sourceName");
                if (name != null) return name.toString();
            } catch (Exception ignored) {}
        }
        if (chunk.getFaqId() != null) {
            return "业务 FAQ 问答";
        }
        return "自研文档 #" + (chunk.getDocumentId() != null ? chunk.getDocumentId().substring(0, Math.min(chunk.getDocumentId().length(), 8)) : "chunk");
    }

    private double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }

    private record ScoredChunk(
            KnowledgeDocumentChunk chunk,
            double finalScore,
            double vectorScore,
            double keywordScore,
            double rerankScore
    ) {}
}
