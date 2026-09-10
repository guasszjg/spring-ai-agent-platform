package com.example.agentplatform.service;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.model.KnowledgeFaq;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.rag.KnowledgeBaseProvider;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.rag.dto.KnowledgeEngineInfo;
import com.example.agentplatform.rag.dto.UpdateFaqRequest;
import com.example.agentplatform.rag.dto.UpdateKnowledgeBaseRequest;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentRepository;
import com.example.agentplatform.repository.KnowledgeFaqRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agentplatform.repository.ResourceGrantRepository;
import com.example.agentplatform.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /**
     * Dify RAG 官方支持的文件扩展名
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "markdown", "pdf", "vtt", "properties", "csv", "html", "htm", "xlsx", "xls", "mdx", "docx", "txt", "md"
    );

    private static final long MAX_FILE_SIZE_BYTES = 15L * 1024 * 1024; // 15MB
    private static final int MAX_BATCH_COUNT = 5;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeFaqRepository faqRepository;
    private final ResourceAuthorizationService resourceAuthorizationService;
    private final ResourceGrantRepository resourceGrantRepository;
    private final Map<String, KnowledgeBaseProvider> providerMap = new HashMap<>();
    private final ObjectMapper objectMapper;
    private final OwnerNameResolver ownerNameResolver;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository,
                                KnowledgeDocumentRepository documentRepository,
                                KnowledgeFaqRepository faqRepository,
                                ResourceAuthorizationService resourceAuthorizationService,
                                ResourceGrantRepository resourceGrantRepository,
                                List<KnowledgeBaseProvider> providers,
                                @Autowired(required = false) ObjectMapper objectMapper,
                                OwnerNameResolver ownerNameResolver) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.faqRepository = faqRepository;
        this.resourceAuthorizationService = resourceAuthorizationService;
        this.resourceGrantRepository = resourceGrantRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.ownerNameResolver = ownerNameResolver;
        for (KnowledgeBaseProvider p : providers) {
            this.providerMap.put(p.getProviderType().toUpperCase(), p);
        }
    }

    private KnowledgeBaseProvider resolveProvider(String providerType) {
        String key = (providerType != null && !providerType.isBlank()) ? providerType.toUpperCase() : "DIFY";
        KnowledgeBaseProvider provider = providerMap.get(key);
        if (provider == null) {
            provider = providerMap.get("DIFY");
        }
        if (provider == null) {
            throw new IllegalStateException("未找到对应的 RAG 知识库服务提供方: " + providerType);
        }
        return provider;
    }

    // ==================== 知识库 CRUD ====================

    @Transactional(readOnly = true)
    public PageResult<KnowledgeBase> searchKnowledgeBases(String keyword, String provider, int page, int size) {
        return searchKnowledgeBases(keyword, provider, page, size, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeBase> searchKnowledgeBases(String keyword, String provider, int page, int size, CurrentActor actor) {
        return searchKnowledgeBases(keyword, provider, page, size, actor, null);
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeBase> searchKnowledgeBases(String keyword, String provider, int page, int size, CurrentActor actor, String ownerId) {
        List<KnowledgeBase> all = knowledgeBaseRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
        Map<String, String> names = ownerNameResolver.usernames(
                all.stream().map(KnowledgeBase::getOwnerId).collect(Collectors.toSet()));
        List<KnowledgeBase> filtered = all.stream()
                .filter(kb -> {
                    if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
                        return false;
                    }
                    if (!OwnerNameResolver.matchesOwner(kb.getOwnerId(), ownerId)) {
                        return false;
                    }
                    if (provider != null && !provider.isBlank()) {
                        if (!provider.trim().equalsIgnoreCase(kb.getProvider())) {
                            return false;
                        }
                    }
                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.trim().toLowerCase();
                        boolean matchName = kb.getName() != null && kb.getName().toLowerCase().contains(kw);
                        boolean matchDesc = kb.getDescription() != null && kb.getDescription().toLowerCase().contains(kw);
                        String ownerName = kb.getOwnerUsername();
                        if (ownerName == null || ownerName.isBlank()) {
                            ownerName = OwnerNameResolver.lookup(names, kb.getOwnerId());
                        }
                        String ownerKw = kw.startsWith("@") ? kw.substring(1) : kw;
                        boolean matchOwner = ownerName != null && ownerName.toLowerCase().contains(ownerKw);
                        if (!matchName && !matchDesc && !matchOwner) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        int total = filtered.size();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        List<KnowledgeBase> pageRecords = filtered.subList(fromIndex, toIndex);
        for (KnowledgeBase kb : pageRecords) {
            if (kb.getOwnerUsername() == null || kb.getOwnerUsername().isBlank()) {
                kb.setOwnerUsername(OwnerNameResolver.lookup(names, kb.getOwnerId()));
            }
        }
        return new PageResult<>(pageRecords, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public KnowledgeBase getKnowledgeBaseById(String id) {
        return getKnowledgeBaseById(id, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public KnowledgeBase getKnowledgeBaseById(String id, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + id));
        if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：无权访问该知识库");
        }
        return kb;
    }

    @Transactional(readOnly = true)
    public KnowledgeEngineInfo getEngineInfo() {
        KnowledgeEngineInfo info = new KnowledgeEngineInfo();
        KnowledgeBaseProvider provider;
        try {
            provider = resolveProvider("DIFY");
        } catch (Exception e) {
            info.setConfigured(false);
            info.setBaseUrl("");
            info.setHost("");
            return info;
        }
        String url = provider.getBaseUrl() != null ? provider.getBaseUrl().trim() : "";
        info.setBaseUrl(url);
        info.setConfigured(!url.isBlank());
        info.setHost(displayHost(url));
        return info;
    }

    static String displayHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String raw = baseUrl.trim();
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                raw = "http://" + raw;
            }
            URI uri = URI.create(raw);
            return uri.getHost() != null ? uri.getHost() : baseUrl.trim();
        } catch (Exception e) {
            return baseUrl.trim();
        }
    }

    /**
     * 按智能体绑定的知识库检索切片，拼成可注入系统提示词的上下文。检索失败不抛错。
     */
    public String buildRetrievalContext(List<String> knowledgeBaseIds, String query) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || query == null || query.isBlank()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        int index = 1;
        for (String kbId : knowledgeBaseIds) {
            if (kbId == null || kbId.isBlank() || index > 12) {
                continue;
            }
            Optional<KnowledgeBase> kbOpt = knowledgeBaseRepository.findById(kbId.trim());
            if (kbOpt.isEmpty()) {
                continue;
            }
            KnowledgeBase kb = kbOpt.get();
            if (Boolean.FALSE.equals(kb.getEnabled())) {
                continue;
            }
            String datasetId = kb.getExternalDatasetId();
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                List<RetrievedChunk> chunks = provider.retrieve(datasetId, query);
                for (RetrievedChunk chunk : chunks) {
                    if (chunk == null || chunk.content() == null || chunk.content().isBlank() || index > 12) {
                        continue;
                    }
                    String content = chunk.content().trim();
                    if (content.length() > 1500) {
                        content = content.substring(0, 1500);
                    }
                    body.append("[").append(index++).append("]");
                    if (chunk.sourceName() != null && !chunk.sourceName().isBlank()) {
                        body.append(" 来源：").append(chunk.sourceName().trim());
                    } else if (kb.getName() != null) {
                        body.append(" 来源：").append(kb.getName());
                    }
                    body.append('\n').append(content).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("知识库 [{}] 检索失败: {}", kb.getName(), e.getMessage());
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        return "【知识库检索结果】以下是可能相关的参考资料。请优先参考其中的有效信息作答；若资料不相关或不足以回答，请严格遵循智能体本身的流程规则继续处理，切勿声明无法从知识库确认。\n\n" + body;
    }

    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveChunks(String kbId, String query, int topK) {
        KnowledgeBase kb = getKnowledgeBaseById(kbId);
        if (kb.getExternalDatasetId() == null || kb.getExternalDatasetId().isBlank()) {
            return List.of();
        }
        KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
        List<RetrievedChunk> chunks = provider.retrieve(kb.getExternalDatasetId(), query);
        if (topK > 0 && chunks.size() > topK) {
            return chunks.subList(0, topK);
        }
        return chunks;
    }


    @Transactional
    public KnowledgeBase createKnowledgeBase(CreateKnowledgeBaseRequest req) {
        return createKnowledgeBase(req, CurrentActor.get());
    }

    @Transactional
    public KnowledgeBase createKnowledgeBase(CreateKnowledgeBaseRequest req, CurrentActor actor) {
        if (actor != null && actor.isViewer()) {
            throw new IllegalStateException("权限不足：只读用户无法创建知识库");
        }
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }

        String providerType = (req.getProvider() != null && !req.getProvider().isBlank()) ? req.getProvider() : "DIFY";
        KnowledgeBaseProvider provider = resolveProvider(providerType);

        String embeddingModel = (req.getEmbeddingModel() != null && !req.getEmbeddingModel().isBlank()) ? req.getEmbeddingModel() : "text-embedding-v3";
        String embeddingProvider = (req.getEmbeddingProvider() != null && !req.getEmbeddingProvider().isBlank()) ? req.getEmbeddingProvider() : "langgenius/tongyi/tongyi";
        String searchMethod = (req.getSearchMethod() != null && !req.getSearchMethod().isBlank()) ? req.getSearchMethod() : "hybrid_search";
        Integer topK = (req.getTopK() != null && req.getTopK() > 0) ? req.getTopK() : 3;
        Boolean rerankEnabled = req.getRerankEnabled() != null ? req.getRerankEnabled() : true;
        String rerankMode = (req.getRerankMode() != null && !req.getRerankMode().isBlank()) ? req.getRerankMode() : "weighted_score";
        String rerankModel = (req.getRerankModel() != null && !req.getRerankModel().isBlank()) ? req.getRerankModel() : "qwen3-rerank";
        String rerankModelProvider = (req.getRerankModelProvider() != null && !req.getRerankModelProvider().isBlank()) ? req.getRerankModelProvider() : "langgenius/tongyi/tongyi";
        Double vectorWeight = req.getVectorWeight() != null ? req.getVectorWeight() : 0.7;
        Double keywordWeight = req.getKeywordWeight() != null ? req.getKeywordWeight() : 0.3;

        // 1. 调用底层 RAG 引擎（Dify）同步创建数据集（带完整权重与重排配置）
        DifyDatasetDto externalDataset = provider.createDataset(
                req.getName().trim(),
                req.getDescription(),
                req.getIndexingTechnique(),
                req.getPermission(),
                embeddingModel,
                embeddingProvider,
                searchMethod,
                topK,
                rerankEnabled,
                rerankMode,
                rerankModel,
                rerankModelProvider,
                vectorWeight,
                keywordWeight
        );

        // 2. 入本地 Spring AI 数据库管理
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription());
        kb.setAvatar((req.getAvatar() != null && !req.getAvatar().isBlank()) ? req.getAvatar() : "📚");
        kb.setProvider(providerType);
        kb.setExternalDatasetId(externalDataset != null ? externalDataset.getId() : null);
        kb.setIndexingTechnique((req.getIndexingTechnique() != null && !req.getIndexingTechnique().isBlank()) ? req.getIndexingTechnique() : "high_quality");
        kb.setPermission((req.getPermission() != null && !req.getPermission().isBlank()) ? req.getPermission() : "only_me");
        kb.setEmbeddingModel(embeddingModel);
        kb.setEmbeddingProvider(embeddingProvider);
        kb.setSearchMethod(searchMethod);
        kb.setTopK(topK);
        kb.setRerankEnabled(rerankEnabled);
        kb.setRerankMode(rerankMode);
        kb.setRerankModel(rerankModel);
        kb.setRerankModelProvider(rerankModelProvider);
        kb.setVectorWeight(vectorWeight);
        kb.setKeywordWeight(keywordWeight);
        kb.setDocumentCount(0);
        kb.setWordCount(0L);
        kb.setFaqCount(0);
        kb.setEnabled(true);

        if (actor != null) {
            kb.setOwnerId(actor.getUserId());
            kb.setOwnerUsername(actor.getUsername());
            kb.setIsSystem(actor.isSuperAdmin());
        } else {
            kb.setIsSystem(true);
            kb.setOwnerUsername("system");
            kb.setOwnerId("system");
        }

        return knowledgeBaseRepository.save(kb);
    }

    @Transactional
    public KnowledgeBase updateKnowledgeBase(String id, UpdateKnowledgeBaseRequest req) {
        return updateKnowledgeBase(id, req, CurrentActor.get());
    }

    @Transactional
    public KnowledgeBase updateKnowledgeBase(String id, UpdateKnowledgeBaseRequest req, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + id));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权修改该知识库");
        }

        if (req.getName() != null && !req.getName().trim().isEmpty()) {
            kb.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            kb.setDescription(req.getDescription());
        }
        if (req.getAvatar() != null && !req.getAvatar().isBlank()) {
            kb.setAvatar(req.getAvatar());
        }
        if (req.getEnabled() != null) {
            kb.setEnabled(req.getEnabled());
        }
        if (req.getSearchMethod() != null && !req.getSearchMethod().isBlank()) {
            kb.setSearchMethod(req.getSearchMethod());
        }
        if (req.getTopK() != null && req.getTopK() > 0) {
            kb.setTopK(req.getTopK());
        }
        if (req.getRerankEnabled() != null) {
            kb.setRerankEnabled(req.getRerankEnabled());
        }
        if (req.getRerankMode() != null && !req.getRerankMode().isBlank()) {
            kb.setRerankMode(req.getRerankMode());
        }
        if (req.getRerankModel() != null && !req.getRerankModel().isBlank()) {
            kb.setRerankModel(req.getRerankModel());
        }
        if (req.getVectorWeight() != null) {
            kb.setVectorWeight(req.getVectorWeight());
        }
        if (req.getKeywordWeight() != null) {
            kb.setKeywordWeight(req.getKeywordWeight());
        }

        // 同步更新外部 Dify 数据集元数据及检索模式与权重
        if (kb.getExternalDatasetId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                provider.updateDataset(kb.getExternalDatasetId(), kb.getName(), kb.getDescription(),
                        kb.getSearchMethod(), kb.getTopK(), kb.getRerankEnabled(),
                        kb.getRerankMode(), kb.getRerankModel(), kb.getRerankModelProvider(),
                        kb.getVectorWeight(), kb.getKeywordWeight());
            } catch (Exception e) {
                log.warn("同步更新底层 RAG 数据集信息失败: {}", e.getMessage());
            }
        }

        return knowledgeBaseRepository.save(kb);
    }

    @Transactional
    public void deleteKnowledgeBase(String id) {
        deleteKnowledgeBase(id, CurrentActor.get());
    }

    @Transactional
    public void deleteKnowledgeBase(String id, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + id));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权删除该知识库");
        }

        // 1. 调用底层 RAG 引擎删除数据集
        if (kb.getExternalDatasetId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                provider.deleteDataset(kb.getExternalDatasetId());
            } catch (Exception e) {
                log.warn("同步删除底层 RAG 数据集失败: {}", e.getMessage());
            }
        }

        // 2. 级联删除权限授权记录、本地文档与 FAQ 数据
        if (resourceGrantRepository != null) {
            resourceGrantRepository.deleteByResourceTypeAndResourceId(ResourceAuthorizationService.TYPE_KNOWLEDGE_BASE, kb.getId());
        }
        documentRepository.deleteByKnowledgeBaseId(kb.getId());
        faqRepository.deleteByKnowledgeBaseId(kb.getId());

        // 3. 删除知识库本体
        knowledgeBaseRepository.delete(kb);
    }

    @Transactional
    public Map<String, Object> syncFromDify() {
        KnowledgeBaseProvider provider = resolveProvider("DIFY");
        List<DifyDatasetDto> externalList = provider.listExternalDatasets();

        int importedKb = 0;
        int importedDocs = 0;

        for (DifyDatasetDto ext : externalList) {
            if (ext.getId() == null) continue;

            Optional<KnowledgeBase> existing = knowledgeBaseRepository.findByExternalDatasetId(ext.getId());
            KnowledgeBase kb;
            if (existing.isEmpty()) {
                kb = new KnowledgeBase();
                kb.setName(ext.getName());
                kb.setDescription(ext.getDescription());
                kb.setProvider("DIFY");
                kb.setExternalDatasetId(ext.getId());
                kb.setIndexingTechnique(ext.getIndexingTechnique());
                kb.setPermission(ext.getPermission());
                kb.setDocumentCount(ext.getDocumentCount() != null ? ext.getDocumentCount() : 0);
                kb.setWordCount(ext.getWordCount() != null ? ext.getWordCount() : 0L);
                kb.setAvatar("📚");
                kb.setIsSystem(true);
                kb.setOwnerUsername("system");
                kb.setOwnerId("system");
                if (ext.getEmbeddingModel() != null) kb.setEmbeddingModel(ext.getEmbeddingModel());
                if (ext.getEmbeddingModelProvider() != null) kb.setEmbeddingProvider(ext.getEmbeddingModelProvider());
                populateRetrievalModel(kb, ext.getRetrievalModelDict());
                kb = knowledgeBaseRepository.save(kb);
                importedKb++;
            } else {
                kb = existing.get();
                if (kb.getIsSystem() == null) {
                    kb.setIsSystem(true);
                    kb.setOwnerUsername("system");
                    kb.setOwnerId("system");
                }
                if (ext.getName() != null) kb.setName(ext.getName());
                if (ext.getDocumentCount() != null) kb.setDocumentCount(ext.getDocumentCount());
                if (ext.getWordCount() != null) kb.setWordCount(ext.getWordCount());
                if (ext.getEmbeddingModel() != null) kb.setEmbeddingModel(ext.getEmbeddingModel());
                if (ext.getEmbeddingModelProvider() != null) kb.setEmbeddingProvider(ext.getEmbeddingModelProvider());
                populateRetrievalModel(kb, ext.getRetrievalModelDict());
                kb = knowledgeBaseRepository.save(kb);
            }

            // 同步该知识库下的文档
            List<DifyDocumentDto> extDocs = provider.listDocuments(ext.getId(), 1, 100);
            for (DifyDocumentDto d : extDocs) {
                if (d.getId() == null) continue;
                Optional<KnowledgeDocument> existingDoc = documentRepository.findByExternalDocId(d.getId());
                if (existingDoc.isEmpty()) {
                    KnowledgeDocument doc = new KnowledgeDocument();
                    doc.setKnowledgeBaseId(kb.getId());
                    doc.setExternalDocId(d.getId());
                    doc.setName(d.getName());
                    String extStr = getFileExtension(d.getName());
                    doc.setExtension(extStr);
                    doc.setIndexingStatus(d.getIndexingStatus() != null ? d.getIndexingStatus() : "completed");
                    doc.setWordCount(d.getWordCount() != null ? d.getWordCount() : 0L);
                    doc.setTokenCount(d.getTokens() != null ? d.getTokens() : 0L);
                    doc.setEnabled(d.getEnabled() != null ? d.getEnabled() : true);
                    documentRepository.save(doc);
                    importedDocs++;
                } else {
                    KnowledgeDocument doc = existingDoc.get();
                    if (d.getIndexingStatus() != null) doc.setIndexingStatus(d.getIndexingStatus());
                    if (d.getWordCount() != null) doc.setWordCount(d.getWordCount());
                    if (d.getTokens() != null) doc.setTokenCount(d.getTokens());
                    documentRepository.save(doc);
                }
            }
            kb.setDocumentCount((int) documentRepository.countByKnowledgeBaseId(kb.getId()));
            knowledgeBaseRepository.save(kb);
        }

        Set<String> remoteIds = new HashSet<>();
        for (DifyDatasetDto ext : externalList) {
            if (ext.getId() != null && !ext.getId().isBlank()) {
                remoteIds.add(ext.getId());
            }
        }
        List<Map<String, String>> stale = new ArrayList<>();
        for (KnowledgeBase local : knowledgeBaseRepository.findByProvider("DIFY")) {
            String datasetId = local.getExternalDatasetId();
            if (datasetId == null || datasetId.isBlank() || remoteIds.contains(datasetId)) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", local.getId());
            item.put("name", local.getName() != null ? local.getName() : "");
            item.put("externalDatasetId", datasetId);
            stale.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalExternal", externalList.size());
        result.put("importedKnowledgeBases", importedKb);
        result.put("importedDocuments", importedDocs);
        result.put("staleCount", stale.size());
        result.put("staleKnowledgeBases", stale);
        result.put("engineHost", displayHost(provider.getBaseUrl()));
        return result;
    }

    // ==================== 文档上传与管理 ====================

    @Transactional
    public List<KnowledgeDocument> uploadDocuments(String knowledgeBaseId, List<MultipartFile> files) {
        return uploadDocuments(knowledgeBaseId, files, CurrentActor.get());
    }

    @Transactional
    public List<KnowledgeDocument> uploadDocuments(String knowledgeBaseId, List<MultipartFile> files, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权向该知识库上传文档");
        }

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择需要上传的文件");
        }
        if (files.size() > MAX_BATCH_COUNT) {
            throw new IllegalArgumentException("每批最多允许上传 " + MAX_BATCH_COUNT + " 个文件，当前包含 " + files.size() + " 个");
        }

        // 校验全部文件格式与大小
        for (MultipartFile file : files) {
            validateFile(file);
        }

        KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
        List<KnowledgeDocument> results = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                // 1. 调用底层 Dify API 上传并创建文档
                DifyDocumentDto difyDoc = provider.uploadDocument(kb.getExternalDatasetId(), file);

                // 2. 存入本地数据库
                KnowledgeDocument doc = new KnowledgeDocument();
                doc.setKnowledgeBaseId(kb.getId());
                doc.setExternalDocId(difyDoc != null ? difyDoc.getId() : null);
                doc.setName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file");
                doc.setExtension(getFileExtension(doc.getName()));
                doc.setFileSize(file.getSize());
                doc.setIndexingStatus(difyDoc != null && difyDoc.getIndexingStatus() != null ? difyDoc.getIndexingStatus() : "waiting");
                doc.setWordCount(difyDoc != null && difyDoc.getWordCount() != null ? difyDoc.getWordCount() : 0L);
                doc.setTokenCount(difyDoc != null && difyDoc.getTokens() != null ? difyDoc.getTokens() : 0L);
                doc.setEnabled(true);

                doc = documentRepository.save(doc);
                results.add(doc);
            } catch (Exception e) {
                log.error("上传文件「{}」失败: {}", file.getOriginalFilename(), e.getMessage(), e);
                throw new RuntimeException("上传文件「" + file.getOriginalFilename() + "」失败: " + e.getMessage(), e);
            }
        }

        // 更新知识库文档统计
        kb.setDocumentCount((int) documentRepository.countByKnowledgeBaseId(kb.getId()));
        knowledgeBaseRepository.save(kb);

        return results;
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> searchDocuments(String knowledgeBaseId, String keyword, String status, int page, int size) {
        return searchDocuments(knowledgeBaseId, keyword, status, page, size, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> searchDocuments(String knowledgeBaseId, String keyword, String status, int page, int size, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权查看该知识库文档");
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        Page<KnowledgeDocument> p = documentRepository.searchDocuments(
                knowledgeBaseId,
                keyword != null ? keyword.trim() : null,
                status != null ? status.trim() : null,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new PageResult<>(p.getContent(), (int) p.getTotalElements(), safePage, safeSize);
    }

    @Transactional
    public void deleteDocument(String knowledgeBaseId, String documentId) {
        deleteDocument(knowledgeBaseId, documentId, CurrentActor.get());
    }

    @Transactional
    public void deleteDocument(String knowledgeBaseId, String documentId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权删除该知识库文档");
        }

        KnowledgeDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));

        if (!doc.getKnowledgeBaseId().equals(kb.getId())) {
            throw new IllegalArgumentException("文档不属于该知识库");
        }

        // 同步在底层 Dify 中删除文档
        if (doc.getExternalDocId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                provider.deleteDocument(kb.getExternalDatasetId(), doc.getExternalDocId());
            } catch (Exception e) {
                log.warn("同步在 Dify 删除文档失败: {}", e.getMessage());
            }
        }

        documentRepository.delete(doc);
        kb.setDocumentCount((int) documentRepository.countByKnowledgeBaseId(kb.getId()));
        knowledgeBaseRepository.save(kb);
    }

    @Transactional
    public KnowledgeDocument refreshDocumentStatus(String knowledgeBaseId, String documentId) {
        return refreshDocumentStatus(knowledgeBaseId, documentId, CurrentActor.get());
    }

    @Transactional
    public KnowledgeDocument refreshDocumentStatus(String knowledgeBaseId, String documentId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权刷新该知识库文档");
        }

        KnowledgeDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));

        if (doc.getExternalDocId() != null && kb.getExternalDatasetId() != null) {
            KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
            List<DifyDocumentDto> docs = provider.listDocuments(kb.getExternalDatasetId(), 1, 100);
            for (DifyDocumentDto d : docs) {
                if (doc.getExternalDocId().equals(d.getId())) {
                    if (d.getIndexingStatus() != null) doc.setIndexingStatus(d.getIndexingStatus());
                    if (d.getWordCount() != null) doc.setWordCount(d.getWordCount());
                    if (d.getTokens() != null) doc.setTokenCount(d.getTokens());
                    return documentRepository.save(doc);
                }
            }
        }
        return doc;
    }

    // ==================== 问答对 (FAQ) 管理 ====================

    @Transactional(readOnly = true)
    public PageResult<KnowledgeFaq> searchFaqs(String knowledgeBaseId, String keyword, String category, int page, int size) {
        return searchFaqs(knowledgeBaseId, keyword, category, page, size, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeFaq> searchFaqs(String knowledgeBaseId, String keyword, String category, int page, int size, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权查看该知识库FAQ");
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        Page<KnowledgeFaq> p = faqRepository.searchFaqs(
                knowledgeBaseId,
                keyword != null ? keyword.trim() : null,
                category != null ? category.trim() : null,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new PageResult<>(p.getContent(), (int) p.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public List<String> getFaqCategories(String knowledgeBaseId) {
        return getFaqCategories(knowledgeBaseId, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public List<String> getFaqCategories(String knowledgeBaseId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (actor != null && !resourceAuthorizationService.canViewKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权查看该知识库FAQ分类");
        }

        List<String> list = faqRepository.findDistinctCategoriesByKnowledgeBaseId(knowledgeBaseId);
        List<String> result = new ArrayList<>();
        result.add("全部");
        for (String c : list) {
            if (c != null && !c.isBlank() && !result.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    @Transactional
    public KnowledgeFaq createFaq(String knowledgeBaseId, CreateFaqRequest req) {
        return createFaq(knowledgeBaseId, req, CurrentActor.get());
    }

    @Transactional
    public KnowledgeFaq createFaq(String knowledgeBaseId, CreateFaqRequest req, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权在该知识库创建FAQ");
        }

        if (req.getQuestion() == null || req.getQuestion().trim().isEmpty()) {
            throw new IllegalArgumentException("FAQ 问题不能为空");
        }
        if (req.getAnswer() == null || req.getAnswer().trim().isEmpty()) {
            throw new IllegalArgumentException("FAQ 答案不能为空");
        }

        KnowledgeFaq faq = new KnowledgeFaq();
        faq.setKnowledgeBaseId(kb.getId());
        faq.setQuestion(req.getQuestion().trim());
        faq.setAnswer(req.getAnswer().trim());
        faq.setCategory((req.getCategory() != null && !req.getCategory().isBlank()) ? req.getCategory().trim() : "通用问答");
        faq.setContentType((req.getContentType() != null && !req.getContentType().isBlank()) ? req.getContentType() : "TEXT");

        if (req.getImageUrls() != null && !req.getImageUrls().isEmpty()) {
            try {
                faq.setImageUrls(objectMapper.writeValueAsString(req.getImageUrls()));
            } catch (Exception e) {
                log.warn("序列化 FAQ 图片 URL 失败: {}", e.getMessage());
            }
        }

        // 同步在外部 Dify 生成问答文本进行向量索引
        if (kb.getExternalDatasetId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                DifyDocumentDto difyDoc = provider.syncFaqDocument(
                        kb.getExternalDatasetId(),
                        null,
                        faq.getQuestion(),
                        faq.getAnswer(),
                        faq.getCategory()
                );
                if (difyDoc != null) {
                    faq.setExternalDocId(difyDoc.getId());
                }
            } catch (Exception e) {
                log.warn("FAQ 同步至 Dify RAG 失败 (已保存至本地): {}", e.getMessage());
            }
        }

        faq = faqRepository.save(faq);
        kb.setFaqCount((int) faqRepository.countByKnowledgeBaseId(kb.getId()));
        knowledgeBaseRepository.save(kb);

        return faq;
    }

    @Transactional
    public KnowledgeFaq updateFaq(String knowledgeBaseId, String faqId, UpdateFaqRequest req) {
        return updateFaq(knowledgeBaseId, faqId, req, CurrentActor.get());
    }

    @Transactional
    public KnowledgeFaq updateFaq(String knowledgeBaseId, String faqId, UpdateFaqRequest req, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权修改该知识库FAQ");
        }

        KnowledgeFaq faq = faqRepository.findById(faqId)
                .orElseThrow(() -> new IllegalArgumentException("FAQ 不存在: " + faqId));

        if (!faq.getKnowledgeBaseId().equals(kb.getId())) {
            throw new IllegalArgumentException("FAQ 不属于该知识库");
        }

        if (req.getQuestion() != null && !req.getQuestion().trim().isEmpty()) {
            faq.setQuestion(req.getQuestion().trim());
        }
        if (req.getAnswer() != null && !req.getAnswer().trim().isEmpty()) {
            faq.setAnswer(req.getAnswer().trim());
        }
        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            faq.setCategory(req.getCategory().trim());
        }
        if (req.getContentType() != null && !req.getContentType().isBlank()) {
            faq.setContentType(req.getContentType());
        }
        if (req.getImageUrls() != null) {
            try {
                faq.setImageUrls(objectMapper.writeValueAsString(req.getImageUrls()));
            } catch (Exception e) {
                log.warn("序列化 FAQ 图片 URL 失败: {}", e.getMessage());
            }
        }
        if (req.getEnabled() != null) {
            faq.setEnabled(req.getEnabled());
        }

        // 重新同步外部 Dify 向量文档
        if (kb.getExternalDatasetId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                DifyDocumentDto difyDoc = provider.syncFaqDocument(
                        kb.getExternalDatasetId(),
                        faq.getExternalDocId(),
                        faq.getQuestion(),
                        faq.getAnswer(),
                        faq.getCategory()
                );
                if (difyDoc != null) {
                    faq.setExternalDocId(difyDoc.getId());
                }
            } catch (Exception e) {
                log.warn("更新 FAQ 同步至 Dify 警告: {}", e.getMessage());
            }
        }

        return faqRepository.save(faq);
    }

    @Transactional
    public void deleteFaq(String knowledgeBaseId, String faqId) {
        deleteFaq(knowledgeBaseId, faqId, CurrentActor.get());
    }

    @Transactional
    public void deleteFaq(String knowledgeBaseId, String faqId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + knowledgeBaseId));
        if (!resourceAuthorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalStateException("权限不足：您无权删除该知识库FAQ");
        }

        KnowledgeFaq faq = faqRepository.findById(faqId)
                .orElseThrow(() -> new IllegalArgumentException("FAQ 不存在: " + faqId));

        if (!faq.getKnowledgeBaseId().equals(kb.getId())) {
            throw new IllegalArgumentException("FAQ 不属于该知识库");
        }

        // 同步删除 Dify 中对应的向量问答文档
        if (faq.getExternalDocId() != null && kb.getExternalDatasetId() != null) {
            try {
                KnowledgeBaseProvider provider = resolveProvider(kb.getProvider());
                provider.deleteFaqDocument(kb.getExternalDatasetId(), faq.getExternalDocId());
            } catch (Exception e) {
                log.warn("删除 Dify FAQ 关联文档警告: {}", e.getMessage());
            }
        }

        faqRepository.delete(faq);
        kb.setFaqCount((int) faqRepository.countByKnowledgeBaseId(kb.getId()));
        knowledgeBaseRepository.save(kb);
    }

    // ==================== FAQ 图片上传辅助 ====================

    public String saveFaqImage(MultipartFile file) {
        return saveFaqImage(file, CurrentActor.get());
    }

    public String saveFaqImage(MultipartFile file, CurrentActor actor) {
        if (actor != null && actor.isViewer()) {
            throw new IllegalStateException("权限不足：只读用户无法上传图片");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传图片不能为空");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("单张图片大小不能超过 10MB");
        }

        String ext = getFileExtension(file.getOriginalFilename()).toLowerCase();
        Set<String> imgExts = Set.of("png", "jpg", "jpeg", "gif", "webp", "svg");
        if (!imgExts.contains(ext)) {
            throw new IllegalArgumentException("仅支持常见图片格式（PNG、JPG、JPEG、GIF、WEBP、SVG）");
        }

        try {
            Path uploadDir = Paths.get("uploads", "knowledge");
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            String filename = "img_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            Path target = uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/knowledge/" + filename;
        } catch (IOException e) {
            log.error("保存上传图片失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片上传失败: " + e.getMessage(), e);
        }
    }

    // ==================== 校验辅助工具 ====================

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件「" + file.getOriginalFilename() + "」内容为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            double mb = Math.round((file.getSize() / (1024.0 * 1024.0)) * 100.0) / 100.0;
            throw new IllegalArgumentException("文件「" + file.getOriginalFilename() + "」大小为 " + mb + "MB，超过 15 MB 限制");
        }
        String ext = getFileExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("文件「" + file.getOriginalFilename() + "」格式「." + ext +
                    "」不受支持。仅支持: MARKDOWN, PDF, VTT, PROPERTIES, CSV, HTML, HTM, XLSX, XLS, MDX, DOCX, TXT, MD");
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    private void populateRetrievalModel(KnowledgeBase kb, Map<String, Object> rDict) {
        if (rDict == null) return;
        Object sm = rDict.get("search_method");
        if (sm != null) kb.setSearchMethod(String.valueOf(sm));
        Object tk = rDict.get("top_k");
        if (tk instanceof Number) kb.setTopK(((Number) tk).intValue());
        Object re = rDict.get("reranking_enable");
        if (re instanceof Boolean) kb.setRerankEnabled((Boolean) re);
        Object rm = rDict.get("reranking_mode");
        if (rm != null) kb.setRerankMode(String.valueOf(rm));

        Object rModel = rDict.get("reranking_model");
        if (rModel instanceof Map) {
            Map<?, ?> rmMap = (Map<?, ?>) rModel;
            if (rmMap.get("reranking_model_name") != null) {
                kb.setRerankModel(String.valueOf(rmMap.get("reranking_model_name")));
            }
            if (rmMap.get("reranking_provider_name") != null) {
                kb.setRerankModelProvider(String.valueOf(rmMap.get("reranking_provider_name")));
            }
        }

        Object weights = rDict.get("weights");
        if (weights instanceof Map) {
            Map<?, ?> wMap = (Map<?, ?>) weights;
            Object kwObj = wMap.get("keyword_setting");
            if (kwObj instanceof Map && ((Map<?, ?>) kwObj).get("keyword_weight") instanceof Number) {
                kb.setKeywordWeight(((Number) ((Map<?, ?>) kwObj).get("keyword_weight")).doubleValue());
            }
            Object vecObj = wMap.get("vector_setting");
            if (vecObj instanceof Map && ((Map<?, ?>) vecObj).get("vector_weight") instanceof Number) {
                kb.setVectorWeight(((Number) ((Map<?, ?>) vecObj).get("vector_weight")).doubleValue());
            }
        }
    }
}
