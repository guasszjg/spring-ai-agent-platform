-- 4.7 助手评测扩充（按通道统计、模型评分 token、回复校验标记）与 4.8 模型规模分级
ALTER TABLE llm_providers
    ADD COLUMN IF NOT EXISTS model_size_tier VARCHAR(16);

ALTER TABLE assistant_messages
    ADD COLUMN IF NOT EXISTS verification_flags TEXT;

ALTER TABLE assistant_eval_runs
    ADD COLUMN IF NOT EXISTS channel_id VARCHAR(64);

ALTER TABLE assistant_eval_runs
    ADD COLUMN IF NOT EXISTS channel_name VARCHAR(128);

ALTER TABLE assistant_eval_runs
    ADD COLUMN IF NOT EXISTS judge_enabled BOOLEAN DEFAULT FALSE;

ALTER TABLE assistant_eval_runs
    ADD COLUMN IF NOT EXISTS judge_tokens BIGINT DEFAULT 0;
