package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocumentChunk;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.pipeline.DocumentChunker;
import com.example.agentplatform.rag.pipeline.KeywordRetriever;
import com.example.agentplatform.rag.pipeline.LocalEmbeddingService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring AI 原生自研 RAG 引擎 Provider 实现
 * 支持全自主可控的本地文档切片、Embedding 向量化、双路混合检索与评分融合
 */
@Service
public class SpringAiKnowledgeBaseProvider implements KnowledgeBaseProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiKnowledgeBaseProvider.class);

    private final KnowledgeDocumentChunkRepository chunkRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentChunker documentChunker;
    private final LocalEmbeddingService embeddingService;
    private final KeywordRetriever keywordRetriever;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiKnowledgeBaseProvider(KnowledgeDocumentChunkRepository chunkRepository,
                                         KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentChunker documentChunker,
                                         LocalEmbeddingService embeddingService,
                                         KeywordRetriever keywordRetriever) {
        this.chunkRepository = chunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.keywordRetriever = keywordRetriever;
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
        dto.setEmbeddingModel(embeddingModel != null ? embeddingModel : "text-embedding-v3");
        dto.setEmbeddingModelProvider(embeddingProvider != null ? embeddingProvider : "spring_ai_native");
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

        // 智能切片
        List<DocumentChunker.ChunkPiece> pieces = documentChunker.splitText(textContent);
        if (pieces.isEmpty() && !textContent.isBlank()) {
            pieces = List.of(new DocumentChunker.ChunkPiece(0, textContent, textContent.length(), totalTokens));
        }

        String docId = UUID.randomUUID().toString();
        List<KnowledgeDocumentChunk> entities = new ArrayList<>();

        for (DocumentChunker.ChunkPiece piece : pieces) {
            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setKnowledgeBaseId(kbId);
            chunk.setDocumentId(docId);
            chunk.setChunkIndex(piece.index());
            chunk.setContent(piece.content());
            chunk.setCharacterCount(piece.charCount());
            chunk.setTokenCount(piece.tokenCount());
            chunk.setEnabled(true);

            // 生成 1024 维向量
            float[] vector = embeddingService.embed(piece.content(), 1024);
            chunk.setEmbedding(embeddingService.serializeVector(vector));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sourceName", fileName);
            meta.put("chunkIndex", piece.index());
            meta.put("charCount", piece.charCount());
            try {
                chunk.setMetadataJson(objectMapper.writeValueAsString(meta));
            } catch (Exception e) {
                chunk.setMetadataJson("{}");
            }

            entities.add(chunk);
        }

        chunkRepository.saveAll(entities);
        log.info("Spring AI 自研切片与向量入库完成: kbId={}, docId={}, fileName={}, chunkCount={}, tokens={}",
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

        String query = request.query();
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 3;
        double threshold = request.scoreThreshold() != null ? request.scoreThreshold() : 0.3;
        String searchMethod = request.searchMethod() != null ? request.searchMethod().toLowerCase() : "hybrid_search";
        boolean rerankEnabled = Boolean.TRUE.equals(request.rerankEnabled());
        double vectorWeight = request.vectorWeight() != null ? request.vectorWeight() : 0.7;
        double keywordWeight = request.keywordWeight() != null ? request.keywordWeight() : 0.3;

        // 计算 Query 向量
        float[] queryVec = embeddingService.embed(query, 1024);

        List<ScoredChunk> candidates = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            float[] chunkVec = embeddingService.deserializeVector(chunk.getEmbedding());
            double vectorScore = (chunkVec.length > 0) ? embeddingService.cosineSimilarity(queryVec, chunkVec) : 0.0;
            double keywordScore = keywordRetriever.computeScore(query, chunk.getContent());

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
                if (chunk.getContent() != null && chunk.getContent().toLowerCase().contains(query.toLowerCase().trim())) {
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

        List<RetrievedChunk> results = new ArrayList<>();
        int count = Math.min(candidates.size(), topK);
        for (int i = 0; i < count; i++) {
            ScoredChunk sc = candidates.get(i);
            KnowledgeDocumentChunk ch = sc.chunk();
            String sourceName = parseSourceName(ch);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("segmentIndex", ch.getChunkIndex() != null ? ch.getChunkIndex() + 1 : 1);
            metadata.put("characterCount", ch.getCharacterCount());

            RetrievedChunk rc = RetrievedChunk.builder()
                    .chunkId(ch.getId())
                    .documentId(ch.getDocumentId())
                    .sourceName(sourceName)
                    .pageNumber(ch.getChunkIndex() != null ? ch.getChunkIndex() + 1 : 1)
                    .content(ch.getContent())
                    .score(round4(sc.finalScore()))
                    .vectorScore(round4(sc.vectorScore()))
                    .keywordScore(round4(sc.keywordScore()))
                    .rerankScore(round4(sc.rerankScore()))
                    .matchType(rerankEnabled ? "RERANK" : ("semantic_search".equals(searchMethod) ? "VECTOR" : "HYBRID"))
                    .tokenCount(ch.getTokenCount() != null ? ch.getTokenCount().intValue() : 0)
                    .metadata(metadata)
                    .build();
            results.add(rc);
        }

        return results;
    }

    private String resolveKnowledgeBaseId(String externalDatasetId) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) {
            return null;
        }
        // 优先根据 externalDatasetId 查找
        Optional<KnowledgeBase> byExt = knowledgeBaseRepository.findByExternalDatasetId(externalDatasetId);
        if (byExt.isPresent()) {
            return byExt.get().getId();
        }
        // 兜底根据 ID 查找
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
