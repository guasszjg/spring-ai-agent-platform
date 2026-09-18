-- V10: OCR 引擎配置入库，与 Embedding / Dify 一样由模型网关页面管理

CREATE TABLE IF NOT EXISTS ocr_configs (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'LOCAL_PADDLE_OCR',
    endpoint VARCHAR(500),
    api_key_encrypted TEXT,
    model_name VARCHAR(120),
    timeout_seconds INTEGER DEFAULT 30,
    is_active BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    last_probe_status VARCHAR(32) DEFAULT 'UNTESTED',
    last_probe_message VARCHAR(500),
    last_probe_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ocr_active ON ocr_configs(is_active);
