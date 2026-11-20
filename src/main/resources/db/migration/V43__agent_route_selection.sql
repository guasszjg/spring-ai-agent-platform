-- 智能体自选的模型通道：主通道、备用通道（模型网关 llm_providers.id）。空值表示跟随平台默认路由。可重复执行。
ALTER TABLE agents ADD COLUMN IF NOT EXISTS primary_provider_id VARCHAR(64);
ALTER TABLE agents ADD COLUMN IF NOT EXISTS fallback_provider_id VARCHAR(64);
