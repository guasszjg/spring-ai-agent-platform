package com.example.agentplatform.rag.parser;

/**
 * 可插拔 OCR 识图服务契约 (Pluggable OCR Service)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 8 章规范
 */
public interface OcrService {

    /**
     * OCR 服务提供方名称（如 PADDLE_OCR、CLOUD_API、MOCK）
     */
    String getProviderName();

    /**
     * 服务是否就绪可用
     */
    boolean isAvailable();

    /**
     * 对图像或扫描件执行文字识别
     */
    OcrResult parseImage(byte[] imageBytes, String filename);

    record OcrResult(
            boolean success,
            String text,
            double confidence,
            int pageCount,
            String errorMessage
    ) {
        public static OcrResult ofSuccess(String text, double confidence, int pageCount) {
            return new OcrResult(true, text, confidence, pageCount, null);
        }

        public static OcrResult ofFailure(String errorMessage) {
            return new OcrResult(false, "", 0.0, 0, errorMessage);
        }
    }
}
