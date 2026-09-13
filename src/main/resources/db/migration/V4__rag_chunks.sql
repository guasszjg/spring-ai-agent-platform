-- V4: RAG 自研引擎物理分段切片存储表
-- 遵循《Spring-AI自研RAG双引擎设计.md》

CREATE TABLE IF NOT EXISTS knowledge_document_chunks (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64),
    faq_id VARCHAR(64),
    chunk_index INTEGER NOT NULL DEFAULT 0,
    content TEXT NOT NULL,
    character_count INTEGER DEFAULT 0,
    token_count BIGINT DEFAULT 0,
    embedding TEXT,
    metadata_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdc_kb_id ON knowledge_document_chunks(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kdc_doc_id ON knowledge_document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_kdc_faq_id ON knowledge_document_chunks(faq_id);
CREATE INDEX IF NOT EXISTS idx_kdc_kb_chunk ON knowledge_document_chunks(knowledge_base_id, chunk_index);
