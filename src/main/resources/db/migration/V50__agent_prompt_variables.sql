-- 为智能体表增加自定义提示词变量配置（JSON 数组，含 key/label/type/required/defaultValue/options/placeholder）
ALTER TABLE agents ADD COLUMN IF NOT EXISTS prompt_variables TEXT;
