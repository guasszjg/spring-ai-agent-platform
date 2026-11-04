-- 记录每天切到备用通道的调用次数；可重复执行。
ALTER TABLE agent_daily_stats ADD COLUMN IF NOT EXISTS failover_count BIGINT NOT NULL DEFAULT 0;
