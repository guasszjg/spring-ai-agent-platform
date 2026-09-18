package com.example.agentplatform.rag.parser;

import com.example.agentplatform.service.OcrConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RoutingOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(RoutingOcrService.class);

    private final LocalPaddleOcrService localPaddleOcrService;
    private final OnlineCloudOcrService onlineCloudOcrService;
    private final OcrConfigService ocrConfigService;

    private String providerMode = "LOCAL_PADDLE_OCR";

    public RoutingOcrService(LocalPaddleOcrService localPaddleOcrService,
                             OnlineCloudOcrService onlineCloudOcrService) {
        this(localPaddleOcrService, onlineCloudOcrService, null);
    }

    @Autowired
    public RoutingOcrService(LocalPaddleOcrService localPaddleOcrService,
                             OnlineCloudOcrService onlineCloudOcrService,
                             @Autowired(required = false) OcrConfigService ocrConfigService) {
        this.localPaddleOcrService = localPaddleOcrService;
        this.onlineCloudOcrService = onlineCloudOcrService;
        this.ocrConfigService = ocrConfigService;
    }

    private OcrService getActiveService() {
        if (resolved().online()) {
            return onlineCloudOcrService;
        }
        return localPaddleOcrService;
    }

    private ResolvedOcrSettings resolved() {
        if (ocrConfigService != null) {
            return ocrConfigService.resolve();
        }
        return new ResolvedOcrSettings(providerMode, false, null, null, null, 30);
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
