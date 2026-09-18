package com.example.agentplatform.rag.parser;

import com.example.agentplatform.service.OcrConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class LocalPaddleOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(LocalPaddleOcrService.class);

    private boolean enabled = false;
    private String endpoint = "";
    private int timeoutSeconds = 30;

    private final OcrConfigService ocrConfigService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LocalPaddleOcrService() {
        this(null);
    }

    @Autowired
    public LocalPaddleOcrService(@Autowired(required = false) OcrConfigService ocrConfigService) {
        this.ocrConfigService = ocrConfigService;
    }

    private ResolvedOcrSettings settings() {
        if (ocrConfigService != null) {
            ResolvedOcrSettings resolved = ocrConfigService.resolve();
            if (!resolved.online()) {
                return resolved;
            }
        }
        return new ResolvedOcrSettings("LOCAL_PADDLE_OCR", enabled, endpoint, "", "", timeoutSeconds);
    }

    @Override
    public String getProviderName() {
        return "LOCAL_PADDLE_OCR";
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
            return OcrResult.ofFailure("OCR 服务未启用");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.ofFailure("待识别图像数据为空");
        }
        if (s.endpoint() == null || s.endpoint().isBlank()) {
            return OcrResult.ofFailure("本地 OCR 接口地址为空");
        }

        try {
            int timeout = s.timeoutSeconds() > 0 ? s.timeoutSeconds() : 30;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(s.endpoint().trim()))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                log.info("PaddleOCR 识别完成: filename={}, length={}", filename, body != null ? body.length() : 0);
                return OcrResult.ofSuccess(body != null ? body.trim() : "", 0.95, 1);
            }
            log.warn("PaddleOCR 服务返回异常状态: status={}", response.statusCode());
            return OcrResult.ofFailure("OCR 服务响应异常状态码: " + response.statusCode());
        } catch (Exception e) {
            log.warn("调用 PaddleOCR 识别失败: {}, 自动降级", e.getMessage());
            return OcrResult.ofFailure("OCR 调用失败: " + e.getMessage());
        }
    }
}
