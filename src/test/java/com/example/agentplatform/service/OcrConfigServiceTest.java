package com.example.agentplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcrConfigServiceTest {

    @Test
    void normalizeProviderMapsAliases() {
        assertEquals("LOCAL_PADDLE_OCR", OcrConfigService.normalizeProvider(null));
        assertEquals("LOCAL_PADDLE_OCR", OcrConfigService.normalizeProvider("paddle"));
        assertEquals("ONLINE_API", OcrConfigService.normalizeProvider("ONLINE_API"));
        assertEquals("ONLINE_API", OcrConfigService.normalizeProvider("cloud"));
        assertEquals("ONLINE_API", OcrConfigService.normalizeProvider("qwen-vl"));
    }
}
