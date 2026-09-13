package com.example.agentplatform.rag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 在线云端 OCR / 多模态视觉 API 适配器 (Online Cloud OCR Service)
 * 支持通义千问-VL (Qwen-VL)、OpenAI (GPT-4o/GPT-4o-mini) 等标准 Vision 兼容接口，
 * 以及通用云端 OCR REST 接口。免去在本地搭建 Python/GPU 环境的运维成本。
 */
@Service
public class OnlineCloudOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(OnlineCloudOcrService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.rag.ocr.enabled:false}")
    private boolean enabled;

    @Value("${app.rag.ocr.online.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String endpoint;

    @Value("${app.rag.ocr.online.api-key:}")
    private String apiKey;

    @Value("${app.rag.ocr.online.model:qwen-vl-plus}")
    private String model;

    @Value("${app.rag.ocr.online.timeout-seconds:60}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getProviderName() {
        return "ONLINE_CLOUD_API";
    }

    @Override
    public boolean isAvailable() {
        return enabled && endpoint != null && !endpoint.isBlank();
    }

    @Override
    public OcrResult parseImage(byte[] imageBytes, String filename) {
        if (!enabled) {
            return OcrResult.ofFailure("在线 OCR 服务未启用 (app.rag.ocr.enabled=false)");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.ofFailure("待识别图像数据为空");
        }

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = guessMimeType(filename);
            String dataUri = "data:" + mimeType + ";base64," + base64Image;

            // 构建兼容 OpenAI / 通义千问-VL 标准多模态视觉请求体
            Map<String, Object> requestBody = Map.of(
                    "model", (model != null && !model.isBlank()) ? model : "qwen-vl-plus",
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

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String extractedText = choices.get(0).path("message").path("content").asText("");
                    log.info("在线云端 OCR 识别完成: filename={}, model={}, length={}", filename, model, extractedText.length());
                    return OcrResult.ofSuccess(extractedText.trim(), 0.98, 1);
                }
                String rawText = response.body();
                return OcrResult.ofSuccess(rawText.trim(), 0.90, 1);
            } else {
                log.warn("在线云端 OCR 响应异常: status={}, body={}", response.statusCode(), response.body());
                return OcrResult.ofFailure("在线 OCR 响应异常: HTTP " + response.statusCode());
            }
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
