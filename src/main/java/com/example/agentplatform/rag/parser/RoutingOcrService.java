package com.example.agentplatform.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 路由 OCR 门面服务 (Routing OCR Facade)
 * 同时支持两种可选模式：
 * 1. LOCAL_PADDLE_OCR: 本地部署 (PaddleOCR / PP-Structure，100% 数据不出内网，私有化闭环)
 * 2. ONLINE_API: 在线云端 API (通义千问-VL / OpenAI / 云厂商标准 Vision OCR API，开箱即用零本地运维)
 */
@Primary
@Service
public class RoutingOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(RoutingOcrService.class);

    private final LocalPaddleOcrService localPaddleOcrService;
    private final OnlineCloudOcrService onlineCloudOcrService;

    @Value("${app.rag.ocr.provider:LOCAL_PADDLE_OCR}")
    private String providerMode; // "LOCAL_PADDLE_OCR" 或 "ONLINE_API"

    public RoutingOcrService(LocalPaddleOcrService localPaddleOcrService,
                             OnlineCloudOcrService onlineCloudOcrService) {
        this.localPaddleOcrService = localPaddleOcrService;
        this.onlineCloudOcrService = onlineCloudOcrService;
    }

    private OcrService getActiveService() {
        if ("ONLINE_API".equalsIgnoreCase(providerMode) || "CLOUD_API".equalsIgnoreCase(providerMode)) {
            return onlineCloudOcrService;
        }
        return localPaddleOcrService;
    }

    @Override
    public String getProviderName() {
        return getActiveService().getProviderName();
    }

    @Override
    public boolean isAvailable() {
        return getActiveService().isAvailable();
    }

    @Override
    public OcrResult parseImage(byte[] imageBytes, String filename) {
        OcrService active = getActiveService();
        log.info("执行 OCR 图像解析: provider={}, filename={}", active.getProviderName(), filename);
        return active.parseImage(imageBytes, filename);
    }
}
