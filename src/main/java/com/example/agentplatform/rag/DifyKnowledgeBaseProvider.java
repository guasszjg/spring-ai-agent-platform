package com.example.agentplatform.rag;

import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DifyKnowledgeBaseProvider implements KnowledgeBaseProvider {

    private static final Logger log = LoggerFactory.getLogger(DifyKnowledgeBaseProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DifyKnowledgeBaseProvider(
            @Value("${app.dify.base-url:}") String baseUrl,
            @Value("${app.dify.api-key:}") String apiKey,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.baseUrl = normalizeDifyBaseUrl(baseUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(60));

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (!this.baseUrl.isBlank()) {
            builder.baseUrl(this.baseUrl);
        }
        if (!this.apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey);
        }
        this.restClient = builder.build();

        if (this.baseUrl.isBlank() || this.apiKey.isBlank()) {
            log.warn("Dify RAG 未完成配置: baseUrl={}, apiKeyConfigured={}",
                    this.baseUrl.isBlank() ? "(空)" : this.baseUrl, !this.apiKey.isBlank());
        } else {
            log.info("Dify RAG 已配置: {}", this.baseUrl);
        }
    }

    static String normalizeDifyBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return url;
    }

    private void ensureConfigured() {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "未配置 Dify Base URL，无法从 Dify 导入。请设置 app.dify.base-url 或环境变量 DIFY_BASE_URL，例如 http://120.79.38.143/v1");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 Dify Dataset API Key。请在 Dify 控制台创建知识库 API Key，并设置 app.dify.api-key 或环境变量 DIFY_API_KEY");
        }
    }

    @Override
    public String getProviderType() {
        return "DIFY";
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public DifyDatasetDto createDataset(String name, String description, String indexingTechnique, String permission,
                                        String embeddingModel, String embeddingProvider, String searchMethod, Integer topK, Boolean rerankEnabled,
                                        String rerankMode, String rerankModel, String rerankModelProvider,
                                        Double vectorWeight, Double keywordWeight) {
        ensureConfigured();
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("name", name);
            req.put("description", description != null ? description : "");
            req.put("indexing_technique", (indexingTechnique != null && !indexingTechnique.isBlank()) ? indexingTechnique : "high_quality");
            req.put("permission", (permission != null && !permission.isBlank()) ? permission : "only_me");
            req.put("provider", "vendor");

            // 1. Embedding 向量模型设置 (默认通义千问 text-embedding-v3)
            String embModel = (embeddingModel != null && !embeddingModel.isBlank()) ? embeddingModel : "text-embedding-v3";
            String embProvider = (embeddingProvider != null && !embeddingProvider.isBlank()) ? embeddingProvider : "langgenius/tongyi/tongyi";
            req.put("embedding_model", embModel);
            req.put("embedding_model_provider", embProvider);

            // 2. 检索模式及权重设置（包含混合检索的权重与重排模型）
            Map<String, Object> retrievalModel = buildRetrievalModelMap(
                    searchMethod, topK, rerankEnabled,
                    rerankMode, rerankModel, rerankModelProvider,
                    vectorWeight, keywordWeight,
                    embModel, embProvider
            );
            req.put("retrieval_model", retrievalModel);

            String response = restClient.post()
                    .uri("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);

            return objectMapper.readValue(response, DifyDatasetDto.class);
        } catch (Exception e) {
            log.error("Dify 创建知识库数据集失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用 Dify RAG 引擎创建知识库失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateDataset(String externalDatasetId, String name, String description,
                              String searchMethod, Integer topK, Boolean rerankEnabled,
                              String rerankMode, String rerankModel, String rerankModelProvider,
                              Double vectorWeight, Double keywordWeight) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return;
        ensureConfigured();
        try {
            Map<String, Object> req = new HashMap<>();
            if (name != null) req.put("name", name);
            if (description != null) req.put("description", description);

            if (searchMethod != null || topK != null || rerankEnabled != null || rerankMode != null || vectorWeight != null) {
                Map<String, Object> retrievalModel = buildRetrievalModelMap(
                        searchMethod, topK, rerankEnabled,
                        rerankMode, rerankModel, rerankModelProvider,
                        vectorWeight, keywordWeight,
                        "text-embedding-v3", "langgenius/tongyi/tongyi"
                );
                req.put("retrieval_model", retrievalModel);
            }

            restClient.patch()
                    .uri("/datasets/{datasetId}", externalDatasetId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Dify 更新知识库数据集失败 (id: {}): {}", externalDatasetId, e.getMessage());
        }
    }

    private Map<String, Object> buildRetrievalModelMap(String searchMethod, Integer topK, Boolean rerankEnabled,
                                                       String rerankMode, String rerankModel, String rerankModelProvider,
                                                       Double vectorWeight, Double keywordWeight,
                                                       String embeddingModel, String embeddingProvider) {
        Map<String, Object> retrievalModel = new HashMap<>();
        String effectiveSearchMethod = (searchMethod != null && !searchMethod.isBlank()) ? searchMethod : "hybrid_search";
        retrievalModel.put("search_method", effectiveSearchMethod);
        retrievalModel.put("top_k", (topK != null && topK > 0) ? topK : 3);
        retrievalModel.put("score_threshold_enabled", false);
        retrievalModel.put("score_threshold", 0.0);

        boolean isRerank = rerankEnabled != null ? rerankEnabled : true;
        retrievalModel.put("reranking_enable", isRerank);

        if ("hybrid_search".equalsIgnoreCase(effectiveSearchMethod)) {
            String mode = (rerankMode != null && !rerankMode.isBlank()) ? rerankMode : "weighted_score";
            retrievalModel.put("reranking_mode", mode);

            // 1. Rerank 模型（通义千问 qwen3-rerank）
            Map<String, Object> rerankModelMap = new HashMap<>();
            rerankModelMap.put("reranking_provider_name", (rerankModelProvider != null && !rerankModelProvider.isBlank()) ? rerankModelProvider : "langgenius/tongyi/tongyi");
            rerankModelMap.put("reranking_model_name", (rerankModel != null && !rerankModel.isBlank()) ? rerankModel : "qwen3-rerank");
            retrievalModel.put("reranking_model", rerankModelMap);

            // 2. 权重设置 (vector_weight + keyword_weight)
            double vWeight = (vectorWeight != null && vectorWeight >= 0 && vectorWeight <= 1.0) ? vectorWeight : 0.7;
            double kWeight = (keywordWeight != null && keywordWeight >= 0 && keywordWeight <= 1.0) ? keywordWeight : Math.round((1.0 - vWeight) * 10.0) / 10.0;

            Map<String, Object> weightsMap = new HashMap<>();
            weightsMap.put("weight_type", "customized");

            Map<String, Object> kwSetting = new HashMap<>();
            kwSetting.put("keyword_weight", kWeight);
            weightsMap.put("keyword_setting", kwSetting);

            Map<String, Object> vecSetting = new HashMap<>();
            vecSetting.put("vector_weight", vWeight);
            vecSetting.put("embedding_model_name", (embeddingModel != null && !embeddingModel.isBlank()) ? embeddingModel : "text-embedding-v3");
            vecSetting.put("embedding_provider_name", (embeddingProvider != null && !embeddingProvider.isBlank()) ? embeddingProvider : "langgenius/tongyi/tongyi");
            weightsMap.put("vector_setting", vecSetting);

            retrievalModel.put("weights", weightsMap);
        }

        return retrievalModel;
    }

    @Override
    public void deleteDataset(String externalDatasetId) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return;
        ensureConfigured();
        try {
            restClient.delete()
                    .uri("/datasets/{datasetId}", externalDatasetId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dify 知识库数据集删除成功: {}", externalDatasetId);
        } catch (Exception e) {
            log.warn("Dify 删除知识库数据集警告 (id: {}): {}", externalDatasetId, e.getMessage());
        }
    }

    @Override
    public List<DifyDatasetDto> listExternalDatasets() {
        ensureConfigured();
        try {
            List<DifyDatasetDto> all = new ArrayList<>();
            for (int page = 1; page <= 5; page++) {
                int currentPage = page;
                String response = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/datasets")
                                .queryParam("page", currentPage)
                                .queryParam("limit", 100)
                                .build())
                        .retrieve()
                        .body(String.class);
                JsonNode root = objectMapper.readTree(response);
                JsonNode dataNode = root.get("data");
                if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
                    break;
                }
                List<DifyDatasetDto> pageItems = objectMapper.readValue(
                        dataNode.toString(), new TypeReference<List<DifyDatasetDto>>() {});
                all.addAll(pageItems);
                if (pageItems.size() < 100) {
                    break;
                }
            }
            return all;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Dify 获取知识库数据集列表失败: {}", e.getMessage(), e);
            throw new IllegalStateException("从 Dify 拉取知识库失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file) {
        ensureConfigured();
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            Map<String, Object> dataConfig = Map.of(
                    "indexing_technique", "high_quality",
                    "process_rule", Map.of("mode", "automatic")
            );
            body.add("data", objectMapper.writeValueAsString(dataConfig));

            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.txt";
                }
            };
            body.add("file", resource);

            String response = restClient.post()
                    .uri("/datasets/{datasetId}/document/create-by-file", externalDatasetId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode docNode = root.has("document") ? root.get("document") : root;
            return objectMapper.readValue(docNode.toString(), DifyDocumentDto.class);
        } catch (Exception e) {
            log.error("Dify 上传文档文件失败: {}", e.getMessage(), e);
            throw new RuntimeException("上传文件到 Dify RAG 引擎失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteDocument(String externalDatasetId, String externalDocId) {
        if (externalDatasetId == null || externalDocId == null) return;
        ensureConfigured();
        try {
            restClient.delete()
                    .uri("/datasets/{datasetId}/documents/{docId}", externalDatasetId, externalDocId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dify 文档删除成功: datasetId={}, docId={}", externalDatasetId, externalDocId);
        } catch (Exception e) {
            log.warn("Dify 删除文档警告: datasetId={}, docId={}, err={}", externalDatasetId, externalDocId, e.getMessage());
        }
    }

    @Override
    public List<DifyDocumentDto> listDocuments(String externalDatasetId, int page, int limit) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return new ArrayList<>();
        ensureConfigured();
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/datasets/{datasetId}/documents")
                            .queryParam("page", Math.max(1, page))
                            .queryParam("limit", Math.max(1, limit))
                            .build(externalDatasetId))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                return objectMapper.readValue(dataNode.toString(), new TypeReference<List<DifyDocumentDto>>() {});
            }
            return new ArrayList<>();
        } catch (Exception e) {
            log.warn("Dify 获取文档列表警告 (datasetId: {}): {}", externalDatasetId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public DifyDocumentDto syncFaqDocument(String externalDatasetId, String existingExternalDocId,
                                          String question, String answer, String category) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return null;
        ensureConfigured();

        // 若已有对应外部文档，先清理旧文档
        if (existingExternalDocId != null && !existingExternalDocId.isBlank()) {
            deleteDocument(externalDatasetId, existingExternalDocId);
        }

        try {
            String text = String.format("【分类】: %s\n【问】: %s\n【答】: %s",
                    category != null ? category : "通用问答", question, answer);

            String title = "FAQ-" + (question.length() > 30 ? question.substring(0, 30) + "..." : question);

            Map<String, Object> req = Map.of(
                    "name", title,
                    "text", text,
                    "indexing_technique", "high_quality",
                    "process_rule", Map.of("mode", "automatic")
            );

            String response = restClient.post()
                    .uri("/datasets/{datasetId}/document/create-by-text", externalDatasetId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode docNode = root.has("document") ? root.get("document") : root;
            return objectMapper.readValue(docNode.toString(), DifyDocumentDto.class);
        } catch (Exception e) {
            log.error("Dify 同步 FAQ 问答为文档失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void deleteFaqDocument(String externalDatasetId, String externalDocId) {
        deleteDocument(externalDatasetId, externalDocId);
    }

    @Override
    public List<RetrievedChunk> retrieve(String externalDatasetId, String query) {
        if (externalDatasetId == null || externalDatasetId.isBlank()
                || query == null || query.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("query", query.trim());
            String response = restClient.post()
                    .uri("/datasets/{datasetId}/retrieve", externalDatasetId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(String.class);
            return parseRetrieveResponse(objectMapper.readTree(response));
        } catch (Exception e) {
            log.warn("Dify 知识库检索失败 (datasetId: {}): {}", externalDatasetId, e.getMessage());
            return new ArrayList<>();
        }
    }

    static List<RetrievedChunk> parseRetrieveResponse(JsonNode root) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        if (root == null) {
            return chunks;
        }
        JsonNode records = root.get("records");
        if (records == null || !records.isArray()) {
            return chunks;
        }
        for (JsonNode record : records) {
            JsonNode segment = record.get("segment");
            if (segment == null || segment.isNull()) {
                continue;
            }
            JsonNode contentNode = segment.get("content");
            if (contentNode == null || contentNode.isNull() || contentNode.asText().isBlank()) {
                continue;
            }
            String sourceName = null;
            JsonNode document = segment.get("document");
            if (document != null && document.hasNonNull("name")) {
                sourceName = document.get("name").asText();
            }
            Double score = record.has("score") && record.get("score").isNumber()
                    ? record.get("score").asDouble()
                    : null;
            chunks.add(new RetrievedChunk(contentNode.asText(), sourceName, score));
        }
        return chunks;
    }
}
