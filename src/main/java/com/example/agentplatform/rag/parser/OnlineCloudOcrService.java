package com.example.agentplatform.rag.parser;

import com.example.agentplatform.service.OcrConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class OnlineCloudOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(OnlineCloudOcrService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private boolean enabled = false;
    private String endpoint = "";
    private String apiKey = "";
    private String model = "";
    private int timeoutSeconds = 60;

    private final OcrConfigService ocrConfigService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OnlineCloudOcrService() {
        this(null);
    }

    @Autowired
    public OnlineCloudOcrService(@Autowired(required = false) OcrConfigService ocrConfigService) {
        this.ocrConfigService = ocrConfigService;
    }

    private ResolvedOcrSettings settings() {
        if (ocrConfigService != null) {
            ResolvedOcrSettings resolved = ocrConfigService.resolve();
            if (resolved.online()) {
                return resolved;
            }
        }
        return new ResolvedOcrSettings("ONLINE_API", enabled, endpoint, apiKey, model, timeoutSeconds);
    }

    @Override
    public String getProviderName() {
        return "ONLINE_CLOUD_API";
    }

    @Override
    public boolean isAvailable() {
        ResolvedOcrSettings s = settings();
        return s.enabled() && s.endpoint() != null && !s.endpoint().isBlank();
    }

    @Override
    public OcrResult parseImage(byte[] imageBytes, String filename) {
        ResolvedOcrSettings s = settings();
        if (!s.enabled()) {
            return OcrResult.ofFailure("在线 OCR 服务未启用");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.ofFailure("待识别图像数据为空");
        }
        if (s.endpoint() == null || s.endpoint().isBlank()) {
            return OcrResult.ofFailure("在线 OCR 接口地址为空");
        }

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = guessMimeType(filename);
            String dataUri = "data:" + mimeType + ";base64," + base64Image;
            String modelName = (s.modelName() != null && !s.modelName().isBlank()) ? s.modelName() : "qwen-vl-plus";

            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", List.of(
                                            Map.of("type", "text", "text", "请完整、精准地提取并转写这幅图像/扫描件中的所有文字与表格内容，按原始阅读顺序排版，不要添加任何额外的解释说明。"),
                                            Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                                    )
                            )
                    ),
                    "max_tokens", 4096,
                    "temperature", 0.1
            );

            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            int timeout = s.timeoutSeconds() > 0 ? s.timeoutSeconds() : 60;

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(s.endpoint().trim()))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

            if (s.apiKey() != null && !s.apiKey().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + s.apiKey().trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String extractedText = choices.get(0).path("message").path("content").asText("");
                    log.info("在线云端 OCR 识别完成: filename={}, model={}, length={}", filename, modelName, extractedText.length());
                    return OcrResult.ofSuccess(extractedText.trim(), 0.98, 1);
                }
                return OcrResult.ofSuccess(response.body().trim(), 0.90, 1);
            }
            log.warn("在线云端 OCR 响应异常: status={}, body={}", response.statusCode(), response.body());
            return OcrResult.ofFailure("在线 OCR 响应异常: HTTP " + response.statusCode());
        } catch (Exception e) {
            log.warn("调用在线云端 OCR 失败: {}, 降级处理", e.getMessage());
            return OcrResult.ofFailure("在线 OCR 调用异常: " + e.getMessage());
        }
    }

    private String guessMimeType(String filename) {
        if (filename == null) return "image/jpeg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "image/jpeg";
    }
}
