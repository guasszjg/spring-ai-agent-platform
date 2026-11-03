-- V25: 工具使用统计、工具单价与平台设置
-- 与其他迁移一样必须可重复执行。

-- 工具单价（元/次），用于估算费用；未填写视为 0
ALTER TABLE platform_tools ADD COLUMN IF NOT EXISTS unit_price NUMERIC(12, 4);
UPDATE platform_tools SET unit_price = 0.036 WHERE code = 'bocha_web_search' AND unit_price IS NULL;
UPDATE platform_tools SET unit_price = 0.005 WHERE code = 'deepseek_web_search' AND unit_price IS NULL;

-- 工具调用按日汇总：每个工具、每个智能体一行
CREATE TABLE IF NOT EXISTS tool_usage_daily (
    stat_date      DATE        NOT NULL,
    tool_code      VARCHAR(80) NOT NULL,
    agent_id       VARCHAR(64) NOT NULL DEFAULT '',
    calls          BIGINT      NOT NULL DEFAULT 0,
    failures       BIGINT      NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_date, tool_code, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_tool_usage_daily_tool ON tool_usage_daily (tool_code, stat_date);

-- 平台级设置（键值）
CREATE TABLE IF NOT EXISTS platform_settings (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500),
    updated_at    TIMESTAMP
);
