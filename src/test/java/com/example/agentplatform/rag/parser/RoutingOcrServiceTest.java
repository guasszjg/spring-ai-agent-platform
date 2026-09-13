package com.example.agentplatform.rag.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class RoutingOcrServiceTest {

    @Test
    @DisplayName("默认情况下路由到本地 PaddleOCR 服务")
    void testRoutesToLocalPaddleOcrByDefault() {
        LocalPaddleOcrService localService = new LocalPaddleOcrService();
        OnlineCloudOcrService onlineService = new OnlineCloudOcrService();

        RoutingOcrService routing = new RoutingOcrService(localService, onlineService);
        ReflectionTestUtils.setField(routing, "providerMode", "LOCAL_PADDLE_OCR");

        assertEquals("LOCAL_PADDLE_OCR", routing.getProviderName());
        assertFalse(routing.isAvailable(), "未开启时应为不可用");
    }

    @Test
    @DisplayName("配置为 ONLINE_API 时路由到在线云端 OCR 服务")
    void testRoutesToOnlineCloudOcr() {
        LocalPaddleOcrService localService = new LocalPaddleOcrService();
        OnlineCloudOcrService onlineService = new OnlineCloudOcrService();

        RoutingOcrService routing = new RoutingOcrService(localService, onlineService);
        ReflectionTestUtils.setField(routing, "providerMode", "ONLINE_API");

        assertEquals("ONLINE_CLOUD_API", routing.getProviderName());

        // 模拟开启在线 OCR
        ReflectionTestUtils.setField(onlineService, "enabled", true);
        ReflectionTestUtils.setField(onlineService, "endpoint", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertTrue(routing.isAvailable());
    }

    @Test
    @DisplayName("在线 OCR 输入为空图片数据时优雅处理")
    void testOnlineOcrEmptyBytes() {
        OnlineCloudOcrService onlineService = new OnlineCloudOcrService();
        ReflectionTestUtils.setField(onlineService, "enabled", true);
        ReflectionTestUtils.setField(onlineService, "endpoint", "https://api.example.com");

        OcrService.OcrResult result = onlineService.parseImage(new byte[0], "test.png");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("为空"));
    }
}
