package com.shimeta.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPriceCatalogTest {

    @Test
    @DisplayName("智谱按完整型号匹配，相近的型号不会串价；中转通道的模型名也能识别")
    void zhipuMatchesExactModel() {
        assertThat(LlmPriceCatalog.rateFor("glm-5.3-flash")).isEqualTo(new LlmPriceCatalog.Rate(0.8, 2.8));
        assertThat(LlmPriceCatalog.rateFor("ZHIPU/GLM-5.3-Flash")).isEqualTo(new LlmPriceCatalog.Rate(0.8, 2.8));
        assertThat(LlmPriceCatalog.rateFor("glm-5.1")).isEqualTo(new LlmPriceCatalog.Rate(6.0, 24.0));
        assertThat(LlmPriceCatalog.rateFor("glm-5")).isEqualTo(new LlmPriceCatalog.Rate(4.0, 18.0));
        assertThat(LlmPriceCatalog.rateFor("glm-4.7-flashx")).isEqualTo(new LlmPriceCatalog.Rate(0.5, 3.0));
        assertThat(LlmPriceCatalog.rateFor("glm-4.7-flash")).isEqualTo(new LlmPriceCatalog.Rate(0.0, 0.0));
    }

    @Test
    @DisplayName("DeepSeek 和通义用当前价格，Pro 不会按 Flash 算")
    void currentRates() {
        assertThat(LlmPriceCatalog.rateFor("deepseek-flash")).isEqualTo(new LlmPriceCatalog.Rate(2.0, 8.0));
        assertThat(LlmPriceCatalog.rateFor("deepseek-v4-pro")).isEqualTo(new LlmPriceCatalog.Rate(9.0, 27.0));
        assertThat(LlmPriceCatalog.rateFor("qwen3.7-flash")).isEqualTo(new LlmPriceCatalog.Rate(0.2, 0.8));
        assertThat(LlmPriceCatalog.rateFor("qwen3.8-max-0902")).isEqualTo(new LlmPriceCatalog.Rate(12.0, 36.0));
    }

    @Test
    @DisplayName("价格表里没有的模型按默认价")
    void unknownModelUsesDefault() {
        assertThat(LlmPriceCatalog.rateFor("glm-5.2")).isEqualTo(new LlmPriceCatalog.Rate(3.0, 9.0));
    }

    @Test
    @DisplayName("免费模型成本为 0；分不到模型的 Token 按默认价计入，不会漏掉")
    void estimateCountsFreeAndUnaccountedTokens() {
        assertThat(LlmPriceCatalog.estimateCny(Map.of("glm-4.7-flash", 1_000_000L), 800_000, 200_000)).isZero();
        // 100 万 Token 免费 + 100 万分不到模型（一半输入一半输出）：0.5×3 + 0.5×9 = 6 元
        assertThat(LlmPriceCatalog.estimateCny(Map.of("glm-4.7-flash", 1_000_000L), 1_000_000, 1_000_000)).isEqualTo(6.0);
    }
}
