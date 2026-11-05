-- 模型网关的 Rerank 模型配置，以及知识库的重排 Token 统计；可重复执行。
CREATE TABLE IF NOT EXISTS rerank_configs (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key_encrypted TEXT,
    custom_headers_encrypted TEXT,
    model_name VARCHAR(120) NOT NULL,
    is_active BOOLEAN DEFAULT FALSE,
    last_probe_status VARCHAR(32) DEFAULT 'UNTESTED',
    last_probe_message VARCHAR(500),
    last_probe_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rerank_active ON rerank_configs(is_active);

ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS rerank_tokens BIGINT DEFAULT 0;
