-- V8: 统一 AI 引擎与模型网关 (支持 Embedding 向量模型与 Dify 知识引擎的多实例配置与动态激活)
-- 彻底杜绝在配置文件硬编码敏感 Key 与服务器 IP

-- 1. Embedding 向量模型配置表 (embedding_configs)
CREATE TABLE IF NOT EXISTS embedding_configs (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'OPENAI',
    base_url VARCHAR(500),
    api_key_encrypted TEXT,
    model_name VARCHAR(120) NOT NULL,
    dimension INTEGER DEFAULT 1024,
    is_active BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    last_probe_status VARCHAR(32) DEFAULT 'UNTESTED',
    last_probe_message VARCHAR(500),
    last_probe_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_emb_active ON embedding_configs(is_active);

-- 2. Dify 外部知识库引擎配置表 (dify_configs)
CREATE TABLE IF NOT EXISTS dify_configs (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    last_probe_status VARCHAR(32) DEFAULT 'UNTESTED',
    last_probe_message VARCHAR(500),
    last_probe_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dify_active ON dify_configs(is_active);
