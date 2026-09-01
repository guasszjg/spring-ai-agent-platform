package com.example.agentplatform.service;

import com.example.agentplatform.model.LlmProviderType;

import java.util.List;
import java.util.Map;

public final class LlmVendorCatalog {

    public record VendorPreset(
            LlmProviderType vendor,
            String name,
            String baseUrl,
            String defaultModel,
            List<String> models,
            String docsUrl,
            String hint
    ) {
    }

    private static final Map<LlmProviderType, VendorPreset> PRESETS = Map.of(
            LlmProviderType.DEEPSEEK, new VendorPreset(
                    LlmProviderType.DEEPSEEK,
                    "DeepSeek",
                    "https://api.deepseek.com/v1",
                    "deepseek-chat",
                    List.of("deepseek-chat", "deepseek-reasoner"),
                    "https://platform.deepseek.com",
                    "兼容 OpenAI Chat Completions，适合作为默认高性价比通道。"
            ),
            LlmProviderType.QIANWEN, new VendorPreset(
                    LlmProviderType.QIANWEN,
                    "通义千问",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-plus",
                    List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long"),
                    "https://help.aliyun.com/zh/model-studio/",
                    "使用阿里云百炼兼容模式 Key，模型名填写 qwen-plus / qwen-max 等。"
            ),
            LlmProviderType.TENCENT, new VendorPreset(
                    LlmProviderType.TENCENT,
                    "腾讯混元",
                    "https://api.hunyuan.cloud.tencent.com/v1",
                    "hunyuan-turbo",
                    List.of("hunyuan-turbo", "hunyuan-large", "hunyuan-standard", "hunyuan-lite"),
                    "https://cloud.tencent.com/document/product/1729",
                    "使用混元 OpenAI 兼容接口与 API Key。"
            ),
            LlmProviderType.BYTEDANCE, new VendorPreset(
                    LlmProviderType.BYTEDANCE,
                    "字节豆包",
                    "https://ark.cn-beijing.volces.com/api/v3",
                    "doubao-seed-1-6-250615",
                    List.of("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115", "doubao-1-5-lite-32k-250115"),
                    "https://www.volcengine.com/docs/82379",
                    "火山方舟请填写推理接入点 ID 作为模型名（例如 ep-xxxxxxxx）。"
            ),
            LlmProviderType.CUSTOM, new VendorPreset(
                    LlmProviderType.CUSTOM,
                    "自定义 OpenAI 兼容",
                    "https://api.openai.com/v1",
                    "gpt-4o-mini",
                    List.of("gpt-4o", "gpt-4o-mini"),
                    "",
                    "任意 OpenAI 兼容网关，需填写 Base URL、模型名与 API Key。"
            )
    );

    private LlmVendorCatalog() {
    }

    public static VendorPreset preset(LlmProviderType vendor) {
        return PRESETS.getOrDefault(vendor, PRESETS.get(LlmProviderType.CUSTOM));
    }

    public static List<VendorPreset> all() {
        return List.of(
                preset(LlmProviderType.DEEPSEEK),
                preset(LlmProviderType.QIANWEN),
                preset(LlmProviderType.TENCENT),
                preset(LlmProviderType.BYTEDANCE),
                preset(LlmProviderType.CUSTOM)
        );
    }
}
