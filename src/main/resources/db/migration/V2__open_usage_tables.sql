-- ==========================================================
-- Flyway Migration V2: Open API Call Logs & Daily Usage Fact Table
-- ==========================================================

-- 1. 开放 API 调用明细日志表 (open_api_call_logs)
CREATE TABLE IF NOT EXISTS open_api_call_logs (
    id VARCHAR(64) PRIMARY KEY,
    ts TIMESTAMP NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    api_key_id VARCHAR(64),
    client_credential_id VARCHAR(64),
    end_user VARCHAR(128),
    agent_id VARCHAR(64),
    conversation_id VARCHAR(64),
    endpoint VARCHAR(64),
    http_status INTEGER,
    deny_reason VARCHAR(64),
    latency_ms INTEGER,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    model VARCHAR(64),
    ip VARCHAR(64),
    request_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_call_logs_owner_ts ON open_api_call_logs(owner_id, ts);
CREATE INDEX IF NOT EXISTS idx_call_logs_key_ts ON open_api_call_logs(api_key_id, ts);
CREATE INDEX IF NOT EXISTS idx_call_logs_agent_ts ON open_api_call_logs(agent_id, ts);

-- 2. 多维开放调用用量统计事实表 (usage_daily)
CREATE TABLE IF NOT EXISTS usage_daily (
    id VARCHAR(120) PRIMARY KEY,
    stat_date DATE NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL DEFAULT '',
    api_key_id VARCHAR(64) NOT NULL DEFAULT '',
    client_credential_id VARCHAR(64) NOT NULL DEFAULT '',
    calls BIGINT NOT NULL DEFAULT 0,
    chat_calls BIGINT NOT NULL DEFAULT 0,
    messages BIGINT NOT NULL DEFAULT 0,
    conversations BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    denied BIGINT NOT NULL DEFAULT 0,
    errors BIGINT NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP,
    CONSTRAINT uk_usage_daily UNIQUE (stat_date, owner_id, agent_id, api_key_id, client_credential_id)
);

CREATE INDEX IF NOT EXISTS idx_usage_daily_owner_date ON usage_daily(owner_id, stat_date);
