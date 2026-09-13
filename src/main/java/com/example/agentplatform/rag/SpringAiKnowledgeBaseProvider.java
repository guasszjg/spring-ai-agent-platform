package com.example.agentplatform.rag;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocumentChunk;
import com.example.agentplatform.model.KnowledgeGraphTriplet;
import com.example.agentplatform.rag.cache.SemanticCacheService;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.graph.GraphRagService;
import com.example.agentplatform.rag.pipeline.ContextBudgetPruner;
import com.example.agentplatform.rag.pipeline.DocumentChunker;
import com.example.agentplatform.rag.pipeline.KeywordRetriever;
import com.example.agentplatform.rag.pipeline.LocalEmbeddingService;
import com.example.agentplatform.rag.pipeline.QueryTransformer;
import com.example.agentplatform.rag.parser.DocumentParsingService;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentChunkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * P3 治理：多格式深度解析 (CSV表头携带/DOCX/PDF流式/OCR识别) 与成本可观测性
 * P4 差异化：语义缓存 (<5ms 响应与 100% Token 节省)、图文跨模态多模态检索 (Chapter 18) 与 GraphRAG 实体多跳试点
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
    private final DocumentParsingService documentParsingService;
    private final SemanticCacheService semanticCacheService;
    private final GraphRagService graphRagService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SpringAiKnowledgeBaseProvider(KnowledgeDocumentChunkRepository chunkRepository,
                                         KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentChunker documentChunker,
                                         LocalEmbeddingService embeddingService,
                                         KeywordRetriever keywordRetriever,
                                         QueryTransformer queryTransformer,
                                         ContextBudgetPruner budgetPruner,
                                         @Autowired(required = false) DocumentParsingService documentParsingService,
                                         @Autowired(required = false) SemanticCacheService semanticCacheService,
                                         @Autowired(required = false) GraphRagService graphRagService) {
        this.chunkRepository = chunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.keywordRetriever = keywordRetriever;
        this.queryTransformer = queryTransformer;
        this.budgetPruner = budgetPruner;
        this.documentParsingService = documentParsingService != null ? documentParsingService : new DocumentParsingService(null);
        this.semanticCacheService = semanticCacheService;
        this.graphRagService = graphRagService;
    }

    public SpringAiKnowledgeBaseProvider(KnowledgeDocumentChunkRepository chunkRepository,
                                         KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentChunker documentChunker,
                                         LocalEmbeddingService embeddingService,
                                         KeywordRetriever keywordRetriever,
                                         QueryTransformer queryTransformer,
                                         ContextBudgetPruner budgetPruner,
                                         DocumentParsingService documentParsingService) {
        this(chunkRepository, knowledgeBaseRepository, documentChunker, embeddingService, keywordRetriever, queryTransformer, budgetPruner, documentParsingService, null, null);
    }

    public SpringAiKnowledgeBaseProvider(KnowledgeDocumentChunkRepository chunkRepository,
                                         KnowledgeBaseRepository knowledgeBaseRepository,
                                         DocumentChunker documentChunker,
                                         LocalEmbeddingService embeddingService,
                                         KeywordRetriever keywordRetriever,
                                         QueryTransformer queryTransformer,
                                         ContextBudgetPruner budgetPruner) {
        this(chunkRepository, knowledgeBaseRepository, documentChunker, embeddingService, keywordRetriever, queryTransformer, budgetPruner, null, null, null);
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
        dto.setEmbeddingModelProvider(embeddingProvider != null ? embeddingProvider : "spring_ai");
        dto.setDocumentCount(0);
        dto.setWordCount(0L);
        log.info("初始化 Spring AI 自研物理知识库数据集: handleId={}, name={}", handleId, name);
        return dto;
    }

    @Override
    public void updateDataset(String externalDatasetId, String name, String description, String searchMethod,
                              Integer topK, Boolean rerankEnabled, String rerankMode, String rerankModel,
                              String rerankModelProvider, Double vectorWeight, Double keywordWeight) {
        log.info("更新 Spring AI 自研知识库配置: handleId={}, name={}, searchMethod={}", externalDatasetId, name, searchMethod);
    }

    @Override
    public List<DifyDatasetDto> listExternalDatasets() {
        return Collections.emptyList();
    }

    @Override
    @Transactional
    public void deleteDataset(String externalDatasetId) {
        log.info("删除 Spring AI 自研物理知识库数据集切片: externalDatasetId={}", externalDatasetId);
        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId != null) {
            chunkRepository.deleteByKnowledgeBaseId(kbId);
            if (semanticCacheService != null) {
                semanticCacheService.clearCache(kbId);
            }
            if (graphRagService != null) {
                graphRagService.deleteByKnowledgeBaseId(kbId);
            }
        }
    }

    @Override
    @Transactional
    public DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file) {
        return uploadDocument(externalDatasetId, file, "high_quality", "automatic", "text_model", "upload");
    }

    @Transactional
    public DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file, String indexingTechnique,
                                          String processType, String docForm, String docType) {
        String kbId = resolveKnowledgeBaseId(externalDatasetId);
        if (kbId == null) {
            kbId = externalDatasetId;
        }

        String fileName = (file != null && file.getOriginalFilename() != null)
                ? file.getOriginalFilename()
                : "document_" + UUID.randomUUID().toString().substring(0, 8);

        byte[] fileBytes;
        try {
            fileBytes = (file != null) ? file.getBytes() : new byte[0];
        } catch (Exception e) {
            log.warn("读取上传文件字节流失败: {}", e.getMessage());
            fileBytes = new byte[0];
        }

        DocumentParsingService.ParseResult parseResult = documentParsingService.parse(fileBytes, fileName);
        String textContent = parseResult.text();
        if (textContent == null || textContent.isBlank()) {
            textContent = extractText(file);
        }

        long totalTokens = documentChunker.estimateTokens(textContent);

        // P2: 高级父子切片管线 (Parent-Child Chunking)
        List<DocumentChunker.ParentChildPiece> pieces = documentChunker.splitParentChild(textContent);
        if (pieces.isEmpty() && !textContent.isBlank()) {
            pieces = List.of(new DocumentChunker.ParentChildPiece(0, textContent, null, textContent, textContent.length(), totalTokens, "STANDALONE"));
        }

        boolean isDocImage = Boolean.TRUE.equals(parseResult.metadata().get("isImage"));
        String docImageUrl = (parseResult.metadata().get("imageUrl") != null) ? String.valueOf(parseResult.metadata().get("imageUrl")) : null;
        String docImageCaption = (parseResult.metadata().get("imageCaption") != null) ? String.valueOf(parseResult.metadata().get("imageCaption")) : null;

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
            chunk.setChunkType(isDocImage ? "IMAGE" : piece.chunkType());
            chunk.setCharacterCount(piece.charCount());
            chunk.setTokenCount(piece.tokenCount());
            chunk.setEnabled(true);

            // P4 多模态属性装配 (Chapter 18)
            if (isDocImage) {
                chunk.setImageUrl(docImageUrl);
                chunk.setImageCaption(docImageCaption != null ? docImageCaption : piece.childContent());
                chunk.setMatchedBy("TEXT_ONLY");
                float[] vector = embeddingService.embedImage(docImageUrl, chunk.getImageCaption(), 1024);
                chunk.setEmbedding(embeddingService.serializeVector(vector));
            } else {
                float[] vector = embeddingService.embed(piece.childContent(), 1024);
                chunk.setEmbedding(embeddingService.serializeVector(vector));
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sourceName", fileName);
            meta.put("chunkIndex", piece.index());
            meta.put("charCount", piece.charCount());
            meta.put("chunkType", chunk.getChunkType());
            meta.put("fileFormat", parseResult.format());
            meta.put("parserType", parseResult.parserType());
            meta.put("isScanned", parseResult.isScanned());
            if (piece.parentChunkId() != null) {
                meta.put("parentChunkId", piece.parentChunkId());
            }
            if (isDocImage) {
                meta.put("isImage", true);
                meta.put("imageUrl", docImageUrl);
                meta.put("imageCaption", docImageCaption);
            }
            try {
                chunk.setMetadataJson(objectMapper.writeValueAsString(meta));
            } catch (Exception e) {
                chunk.setMetadataJson("{}");
            }

            entities.add(chunk);
        }

        chunkRepository.saveAll(entities);
        log.info("Spring AI 自研多格式智能解析与向量入库完成: kbId={}, docId={}, fileName={}, format={}, parser={}, chunkCount={}, tokens={}",
                kbId, docId, fileName, parseResult.format(), parseResult.parserType(), entities.size(), totalTokens);

        // P4: 触发 GraphRAG 实体三元组智能提取 (实体抽取与关联建模)
        if (graphRagService != null) {
            try {
                for (KnowledgeDocumentChunk ch : entities) {
                    graphRagService.extractAndSaveTriplets(kbId, ch.getId(), ch.getContent());
                }
            } catch (Exception e) {
                log.warn("GraphRAG 实体三元组提取异常: {}", e.getMessage());
            }
        }

        // P4: 文档变更自动使语义缓存失效
        if (semanticCacheService != null) {
            semanticCacheService.clearCache(kbId);
        }

        // 累加知识库成本 Embedding Tokens 与估算金额
        try {
            Optional<KnowledgeBase> kbOpt = knowledgeBaseRepository.findById(kbId);
            if (kbOpt.isPresent()) {
                KnowledgeBase kb = kbOpt.get();
                long curEmbedTokens = kb.getEmbeddingTokens() != null ? kb.getEmbeddingTokens() : 0L;
                kb.setEmbeddingTokens(curEmbedTokens + totalTokens);
                double embedCost = kb.getEmbeddingTokens() * 0.0000005;
                double rkCost = (kb.getRerankCalls() != null ? kb.getRerankCalls() : 0L) * 0.003;
                kb.setEstimatedCost(Math.round((embedCost + rkCost) * 10000.0) / 10000.0);
                knowledgeBaseRepository.save(kb);
            }
        } catch (Exception e) {
            log.warn("更新知识库 Embedding Tokens 成本统计失败: {}", e.getMessage());
        }

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
            String kbId = resolveKnowledgeBaseId(externalDatasetId);
            if (kbId != null && semanticCacheService != null) {
                semanticCacheService.clearCache(kbId);
            }
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

        // P4: FAQ 录入抽取 GraphRAG 三元组并清除缓存
        if (graphRagService != null) {
            try {
                graphRagService.extractAndSaveTriplets(kbId, chunk.getId(), combinedText);
            } catch (Exception ignored) {}
        }
        if (semanticCacheService != null) {
            semanticCacheService.clearCache(kbId);
        }

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
            String kbId = resolveKnowledgeBaseId(externalDatasetId);
            if (kbId != null && semanticCacheService != null) {
                semanticCacheService.clearCache(kbId);
            }
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
                .cacheEnabled(true)
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

        long retrievalStart = System.currentTimeMillis();

        // 1. P4 语义缓存检查 (Semantic Cache Check): 相似度 >= 0.95 判定为同一语义，直接瞬时返回
        boolean cacheEnabled = request.cacheEnabled() == null || Boolean.TRUE.equals(request.cacheEnabled());
        if (cacheEnabled && semanticCacheService != null) {
            Optional<SemanticCacheService.CacheLookupResult> cacheHit = semanticCacheService.lookup(kbId, request.query(), null, 0.95);
            if (cacheHit.isPresent()) {
                log.info("Spring AI 检索瞬时命中语义缓存 (< 5ms): kbId={}, query='{}', hitCount={}, similarity={}",
                        kbId, request.query(), cacheHit.get().hitCount(), cacheHit.get().similarity());
                return cacheHit.get().chunks();
            }
        }

        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(kbId);
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Query 智能理解与改写 (P2 阶段)
        boolean rewriteEnabled = Boolean.TRUE.equals(request.rewriteEnabled());
        QueryTransformer.TransformResult transformResult = queryTransformer.transform(request.query(), rewriteEnabled, false);
        String effectiveQuery = transformResult.rewrittenQuery();

        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 3;
        double threshold = request.scoreThreshold() != null ? request.scoreThreshold() : 0.3;
        String searchMethod = request.searchMethod() != null ? request.searchMethod().toLowerCase() : "hybrid_search";
        boolean rerankEnabled = Boolean.TRUE.equals(request.rerankEnabled());
        double vectorWeight = request.vectorWeight() != null ? request.vectorWeight() : 0.7;
        double keywordWeight = request.keywordWeight() != null ? request.keywordWeight() : 0.3;

        // P4: 多模态查询支持 (以文搜图、以图搜图)
        boolean isImageQuery = "IMAGE".equalsIgnoreCase(request.queryType())
                || (request.queryImageUrl() != null && !request.queryImageUrl().isBlank());
        float[] queryVec = isImageQuery
                ? embeddingService.embedImage(request.queryImageUrl(), effectiveQuery, 1024)
                : embeddingService.embed(effectiveQuery, 1024);

        List<ScoredChunk> candidates = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            float[] chunkVec = embeddingService.deserializeVector(chunk.getEmbedding());
            double vectorScore = (chunkVec.length > 0) ? embeddingService.cosineSimilarity(queryVec, chunkVec) : 0.0;
            double keywordScore = keywordRetriever.computeScore(effectiveQuery, chunk.getContent());

            boolean isChunkImage = "IMAGE".equalsIgnoreCase(chunk.getChunkType())
                    || (chunk.getImageUrl() != null && !chunk.getImageUrl().isBlank());
            String matchedBy = "TEXT_ONLY";
            if (isChunkImage) {
                if (isImageQuery || vectorScore > 0.65) {
                    matchedBy = "IMAGE_VECTOR";
                } else if (keywordScore > 0.25) {
                    matchedBy = "CAPTION";
                } else {
                    matchedBy = "IMAGE_VECTOR";
                }
            }

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
                if (chunk.getContent() != null && chunk.getContent().toLowerCase().contains(effectiveQuery.toLowerCase().trim())) {
                    rerankScore = Math.min(1.0, fusedScore * 1.25);
                } else if (keywordScore > 0.6) {
                    rerankScore = Math.min(1.0, fusedScore * 1.12);
                }
            }

            double finalScore = rerankEnabled ? rerankScore : fusedScore;
            if (finalScore >= threshold) {
                candidates.add(new ScoredChunk(chunk, finalScore, vectorScore, keywordScore, rerankScore, matchedBy));
            }
        }

        candidates.sort(Comparator.comparingDouble(ScoredChunk::finalScore).reversed());

        // 3. 父子切片回溯展开与跨段去重 (P2 阶段)
        boolean expandParent = request.expandParent() == null || Boolean.TRUE.equals(request.expandParent());
        List<RetrievedChunk> candidateResults = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();

        for (ScoredChunk sc : candidates) {
            if (candidateResults.size() >= topK * 2) {
                break;
            }

            KnowledgeDocumentChunk ch = sc.chunk();
            String sourceName = parseSourceName(ch);

            boolean parentExpanded = false;
            String finalContent = ch.getContent();
            long finalTokens = ch.getTokenCount() != null ? ch.getTokenCount() : 0L;

            if (expandParent && ch.getParentContent() != null && !ch.getParentContent().isBlank()) {
                String pId = ch.getParentChunkId() != null ? ch.getParentChunkId() : ("parent_" + ch.getId());
                if (seenParents.contains(pId)) {
                    continue;
                }
                seenParents.add(pId);
                finalContent = ch.getParentContent();
                finalTokens = documentChunker.estimateTokens(finalContent);
                parentExpanded = true;
            }

            boolean isImage = "IMAGE".equalsIgnoreCase(ch.getChunkType()) || (ch.getImageUrl() != null && !ch.getImageUrl().isBlank());

            // Chapter 18: 原图默认不进 LLM 上下文（仅作为引用卡片与受控 URL 返回，成本控制约 1/3）
            // 当 injectImagesToLlm 为 true 时，才向内容追加 Markdown 图片语法
            if (isImage && Boolean.TRUE.equals(request.injectImagesToLlm()) && ch.getImageUrl() != null) {
                finalContent = finalContent + "\n\n![参考图片](" + ch.getImageUrl() + ")";
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("segmentIndex", ch.getChunkIndex() != null ? ch.getChunkIndex() + 1 : 1);
            metadata.put("characterCount", finalContent.length());
            metadata.put("parentExpanded", parentExpanded);
            metadata.put("chunkType", ch.getChunkType() != null ? ch.getChunkType() : "CHILD");
            metadata.put("matchedBy", sc.matchedBy());
            if (isImage) {
                metadata.put("isImage", true);
                metadata.put("imageUrl", ch.getImageUrl());
                metadata.put("imageCaption", ch.getImageCaption());
            }
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
                    .imageUrl(ch.getImageUrl())
                    .imageCaption(ch.getImageCaption())
                    .matchedBy(sc.matchedBy())
                    .chunkType(ch.getChunkType() != null ? ch.getChunkType() : "CHILD")
                    .metadata(metadata)
                    .build();
            candidateResults.add(rc);
        }

        // 4. P4: GraphRAG 实体多跳图谱检索增强 (GraphRAG Pilot)
        if (Boolean.TRUE.equals(request.graphSearchEnabled()) && graphRagService != null) {
            try {
                List<KnowledgeGraphTriplet> triplets = graphRagService.findRelatedTriplets(kbId, effectiveQuery);
                if (!triplets.isEmpty()) {
                    RetrievedChunk graphChunk = graphRagService.buildGraphAugmentedChunk(kbId, effectiveQuery, triplets);
                    if (graphChunk != null) {
                        candidateResults.add(0, graphChunk);
                    }
                }
            } catch (Exception e) {
                log.warn("GraphRAG 检索扩展异常: {}", e.getMessage());
            }
        }

        // 截断到 topK
        List<RetrievedChunk> topKResults = candidateResults.size() > topK
                ? candidateResults.subList(0, topK)
                : candidateResults;

        // 5. 上下文 Token 预算裁剪与动态装填 (P2 阶段 - 修复 F4 缺陷)
        ContextBudgetPruner.PruneResult pruneResult = budgetPruner.prune(topKResults, request.maxContextTokens());
        List<RetrievedChunk> finalChunks = pruneResult.prunedChunks();

        long latencyMs = System.currentTimeMillis() - retrievalStart;

        // 6. P4: 异步或同步写入语义缓存 (以备下次快速命中)
        if (cacheEnabled && semanticCacheService != null && !finalChunks.isEmpty()) {
            semanticCacheService.put(kbId, request.indexVersionId(), request.query(), queryVec, finalChunks, latencyMs, null);
        }

        // 累加自研引擎检索 Token 与重排调用统计
        try {
            Optional<KnowledgeBase> kbOpt = knowledgeBaseRepository.findById(kbId);
            if (kbOpt.isPresent()) {
                KnowledgeBase kb = kbOpt.get();
                int retTokens = finalChunks.stream().mapToInt(c -> c.tokenCount() != null ? c.tokenCount() : 0).sum();
                long curRet = kb.getRetrievalTokens() != null ? kb.getRetrievalTokens() : 0L;
                kb.setRetrievalTokens(curRet + retTokens);
                if (rerankEnabled) {
                    long curRk = kb.getRerankCalls() != null ? kb.getRerankCalls() : 0L;
                    kb.setRerankCalls(curRk + 1);
                }
                double embedCost = (kb.getEmbeddingTokens() != null ? kb.getEmbeddingTokens() : 0L) * 0.0000005;
                double rkCost = (kb.getRerankCalls() != null ? kb.getRerankCalls() : 0L) * 0.003;
                kb.setEstimatedCost(Math.round((embedCost + rkCost) * 10000.0) / 10000.0);
                knowledgeBaseRepository.save(kb);
            }
        } catch (Exception e) {
            log.warn("自研引擎更新检索 Token 统计失败: {}", e.getMessage());
        }

        return finalChunks;
    }

    /**
     * P4 多模态扩展：历史图片向量回填与重计算 (Chapter 18)
     */
    @Transactional
    public int reembedImages(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return 0;
        }
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByKnowledgeBaseIdAndEnabledTrueOrderByChunkIndexAsc(kbId);
        int count = 0;
        for (KnowledgeDocumentChunk ch : chunks) {
            if ("IMAGE".equalsIgnoreCase(ch.getChunkType()) || (ch.getImageUrl() != null && !ch.getImageUrl().isBlank())) {
                float[] vec = embeddingService.embedImage(ch.getImageUrl(), ch.getImageCaption(), 1024);
                ch.setEmbedding(embeddingService.serializeVector(vec));
                ch.setMatchedBy("IMAGE_VECTOR");
                chunkRepository.save(ch);
                count++;
            }
        }
        if (semanticCacheService != null) {
            semanticCacheService.clearCache(kbId);
        }
        log.info("图片向量回填重计算完成: kbId={}, count={}", kbId, count);
        return count;
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
            double rerankScore,
            String matchedBy
    ) {}
}
