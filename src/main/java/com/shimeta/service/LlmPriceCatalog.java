package com.shimeta.service;

import java.util.Locale;
import java.util.Map;

/**
 * Official list prices in CNY per 1 million tokens (cache miss / standard).
 * DeepSeek uses weekday peak rates so the estimate is conservative.
 * 价格表里没有的模型按 DEFAULT 估算；智谱按输入 32K 以内的档位。
 */
public final class LlmPriceCatalog {

    public record Rate(double inputCnyPerMillion, double outputCnyPerMillion) {
    }

    private static final Rate DEFAULT = new Rate(3.0, 9.0);

    /** 智谱型号相近（glm-5 / glm-5.1 / glm-5.3-flash），按完整型号匹配；中转通道的 ZHIPU/GLM-5.1 取斜杠后的部分 */
    private static final Map<String, Rate> ZHIPU_RATES = Map.of(
            "glm-5.3-flashx", new Rate(2.0, 7.0),
            "glm-5.3-flash", new Rate(0.8, 2.8),
            "glm-5.1", new Rate(6.0, 24.0),
            "glm-5", new Rate(4.0, 18.0),
            "glm-4.7", new Rate(3.0, 14.0),
            "glm-4.7-flashx", new Rate(0.5, 3.0),
            "glm-4.7-flash", new Rate(0.0, 0.0),
            "glm-4.5-air", new Rate(0.8, 6.0));

    private LlmPriceCatalog() {
    }

    public static Rate rateFor(String modelName) {
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        Rate zhipu = ZHIPU_RATES.get(name.substring(name.lastIndexOf('/') + 1));
        if (zhipu != null) {
            return zhipu;
        }
        if (name.contains("deepseek") && (name.contains("pro") || name.contains("reasoner"))) {
            return new Rate(9.0, 27.0);
        }
        if (name.contains("deepseek") || name.contains("v4-flash")) {
            return new Rate(2.0, 8.0);
        }
        if (name.contains("qwen3.8-max")) {
            return new Rate(12.0, 36.0);
        }
        if (name.contains("qwen3.7-plus")) {
            return new Rate(2.0, 8.0);
        }
        if (name.contains("qwen3.7-flash")) {
            return new Rate(0.2, 0.8);
        }
        if (name.contains("qwen-flash")) {
            return new Rate(0.15, 1.5);
        }
        if (name.contains("qwen-max")) {
            return new Rate(2.4, 9.6);
        }
        if (name.contains("qwen3-max") || name.contains("qwen3.max")) {
            return new Rate(2.5, 10.0);
        }
        if (name.contains("qwen-plus")) {
            return new Rate(0.8, 2.0);
        }
        if (name.contains("qwen3.5-plus") || name.contains("qwen3-plus")) {
            return new Rate(0.8, 4.8);
        }
        if (name.contains("qwen-turbo") || name.contains("qwen3.5-flash")) {
            return new Rate(0.2, 2.0);
        }
        if (name.contains("qwen") || name.contains("qianwen") || name.contains("tongyi")) {
            return new Rate(0.8, 4.8);
        }
        if (name.contains("hunyuan-lite") || name.contains("hunyuan-t1")) {
            return new Rate(0.8, 2.0);
        }
        if (name.contains("hunyuan-large") || name.contains("hunyuan-standard")) {
            return new Rate(4.0, 12.0);
        }
        if (name.contains("hunyuan")) {
            return new Rate(2.0, 8.0);
        }
        if (name.contains("doubao") && (name.contains("lite") || name.contains("seed-lite"))) {
            return new Rate(0.3, 0.6);
        }
        if (name.contains("doubao") && (name.contains("1-6") || name.contains("1.6") || name.contains("seed-1-6"))) {
            return new Rate(0.8, 8.0);
        }
        if (name.contains("doubao") || name.contains("ep-")) {
            return new Rate(0.8, 2.0);
        }
        if (name.contains("gpt-4o-mini") || name.contains("gpt-4.1-mini") || name.contains("gpt-4.1-nano")) {
            return new Rate(1.05, 4.2);
        }
        if (name.contains("gpt-4o") || name.contains("gpt-4.1") || name.contains("gpt-4-turbo")) {
            return new Rate(17.5, 70.0);
        }
        if (name.contains("claude")) {
            return new Rate(21.0, 105.0);
        }
        return DEFAULT;
    }

    public static double estimateCny(Map<String, Long> tokensByModel, long promptTokens, long completionTokens) {
        long total = promptTokens + completionTokens;
        if (tokensByModel == null || tokensByModel.isEmpty()) {
            if (total <= 0) {
                return 0;
            }
            return round(cost(DEFAULT, promptTokens, completionTokens));
        }
        long accounted = tokensByModel.values().stream().mapToLong(Long::longValue).sum();
        double promptShare = total <= 0 ? 0.57 : promptTokens * 1.0 / total;
        double sum = 0;
        for (Map.Entry<String, Long> entry : tokensByModel.entrySet()) {
            long tokens = Math.max(0, entry.getValue());
            long prompt = Math.round(tokens * promptShare);
            long completion = Math.max(0, tokens - prompt);
            sum += cost(rateFor(entry.getKey()), prompt, completion);
        }
        // 分不到模型的部分（会话已删除、流式中断的估算值）按默认价计入
        long unaccounted = total - accounted;
        if (unaccounted > 0) {
            long prompt = Math.round(unaccounted * promptShare);
            sum += cost(DEFAULT, prompt, unaccounted - prompt);
        }
        return round(sum);
    }

    private static double cost(Rate rate, long promptTokens, long completionTokens) {
        return promptTokens / 1_000_000.0 * rate.inputCnyPerMillion()
                + completionTokens / 1_000_000.0 * rate.outputCnyPerMillion();
    }

    private static double round(double value) {
        if (value <= 0) {
            return 0;
        }
        if (value < 0.01) {
            return Math.round(value * 10000.0) / 10000.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
