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
        this.baseUrl = (baseUrl != null && baseUrl.endsWith("/")) ? baseUrl.substring(0, baseUrl.length() - 1) : (baseUrl != null ? baseUrl : "");
        this.apiKey = apiKey != null ? apiKey : "";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey)
                .build();
    }

    @Override
    public String getProviderType() {
        return "DIFY";
    }

    @Override
    public DifyDatasetDto createDataset(String name, String description, String indexingTechnique, String permission) {
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("name", name);
            req.put("description", description != null ? description : "");
            req.put("indexing_technique", (indexingTechnique != null && !indexingTechnique.isBlank()) ? indexingTechnique : "high_quality");
            req.put("permission", (permission != null && !permission.isBlank()) ? permission : "only_me");

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
    public void updateDataset(String externalDatasetId, String name, String description) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return;
        try {
            Map<String, Object> req = new HashMap<>();
            if (name != null) req.put("name", name);
            if (description != null) req.put("description", description);

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

    @Override
    public void deleteDataset(String externalDatasetId) {
        if (externalDatasetId == null || externalDatasetId.isBlank()) return;
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
        try {
            String response = restClient.get()
                    .uri("/datasets?page=1&limit=100")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                return objectMapper.readValue(dataNode.toString(), new TypeReference<List<DifyDatasetDto>>() {});
            }
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Dify 获取知识库数据集列表失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public DifyDocumentDto uploadDocument(String externalDatasetId, MultipartFile file) {
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
        try {
            String response = restClient.get()
                    .uri("/datasets/{datasetId}/documents?page={page}&limit={limit}",
                            externalDatasetId, Math.max(1, page), Math.max(1, limit))
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
}
