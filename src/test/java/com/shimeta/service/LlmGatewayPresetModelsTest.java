package com.shimeta.service;

import com.shimeta.model.LlmProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGatewayPresetModelsTest {

    @Test
    @DisplayName("探测结果补上厂商预设模型：接口列表不返回的 glm-4.7-flash 不会被冲掉")
    void mergesPresetModelsAfterDiscovered() {
        List<String> merged = LlmGatewayService.withPresetModels(LlmProviderType.ZHIPU,
                List.of("glm-4.5", "GLM-5.3-Flash"));

        assertThat(merged).startsWith("glm-4.5", "GLM-5.3-Flash")
                .contains("glm-4.7-flash", "glm-4.7-flashx")
                .doesNotContain("glm-5.3-flash");
    }

    @Test
    @DisplayName("自定义通道和探测没拿到列表时保持原样")
    void keepsCustomAndEmptyResults() {
        assertThat(LlmGatewayService.withPresetModels(LlmProviderType.CUSTOM, List.of("m1"))).containsExactly("m1");
        assertThat(LlmGatewayService.withPresetModels(LlmProviderType.ZHIPU, List.of())).isEmpty();
    }
}
