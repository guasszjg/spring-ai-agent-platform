-- V18: 平台 AI 助手专用模型（P3）
-- 在模型网关中可为助手单独指定通道与模型（例如更便宜、工具调用更稳的模型）；不配置时继续走默认通道。
-- 与其他迁移一样必须可重复执行。
ALTER TABLE gateway_policies ADD COLUMN IF NOT EXISTS assistant_provider_id VARCHAR(64);
ALTER TABLE gateway_policies ADD COLUMN IF NOT EXISTS assistant_model VARCHAR(128);
