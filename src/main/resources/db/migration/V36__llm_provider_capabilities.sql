-- 模型通道能力：是否支持工具调用（为空时按协议和厂商推断），上下文窗口（为空表示不限制）。可重复执行。
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS supports_tools BOOLEAN;
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS context_window INTEGER;
