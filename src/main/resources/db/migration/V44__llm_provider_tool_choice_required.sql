-- 模型通道能力：是否支持强制工具调用（tool_choice=required）。可重复执行。
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS supports_tool_choice_required BOOLEAN;
