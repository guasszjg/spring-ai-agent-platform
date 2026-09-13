package com.example.agentplatform.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 本地 PaddleOCR / PP-Structure 服务适配器
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 8.2 节：
 * 默认关闭，按需开启；数据不出内网，支持 HTTP 契约与平滑降级
 */
@Service
public class LocalPaddleOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(LocalPaddleOcrService.class);

    @Value("${app.rag.ocr.enabled:false}")
    private boolean enabled;

    @Value("${app.rag.ocr.endpoint:http://localhost:8866/predict/ocr_system}")
    private String endpoint;

    @Value("${app.rag.ocr.timeout-seconds:30}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String getProviderName() {
        return "LOCAL_PADDLE_OCR";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public OcrResult parseImage(byte[] imageBytes, String filename) {
        if (!enabled) {
            return OcrResult.ofFailure("OCR 服务未启用 (app.rag.ocr.enabled=false)");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.ofFailure("待识别图像数据为空");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                log.info("PaddleOCR 识别完成: filename={}, length={}", filename, body != null ? body.length() : 0);
                return OcrResult.ofSuccess(body != null ? body.trim() : "", 0.95, 1);
            } else {
                log.warn("PaddleOCR 服务返回异常状态: status={}", response.statusCode());
                return OcrResult.ofFailure("OCR 服务响应异常状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            log.warn("调用 PaddleOCR 识别失败: {}, 自动降级", e.getMessage());
            return OcrResult.ofFailure("OCR 调用失败: " + e.getMessage());
        }
    }
}
