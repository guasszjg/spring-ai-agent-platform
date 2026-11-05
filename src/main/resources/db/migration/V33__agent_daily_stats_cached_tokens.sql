-- 记录每天输入 Token 中命中提示词缓存的部分；可重复执行。
ALTER TABLE agent_daily_stats ADD COLUMN IF NOT EXISTS cached_prompt_tokens BIGINT NOT NULL DEFAULT 0;
