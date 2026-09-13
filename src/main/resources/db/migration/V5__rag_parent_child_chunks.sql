-- V5: RAG 自研引擎父子分块与高级切片支持
-- 遵循《Spring-AI自研RAG双引擎设计.md》第 7.3 节

ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS parent_chunk_id VARCHAR(64);
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS parent_content TEXT;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(32) DEFAULT 'CHILD';

CREATE INDEX IF NOT EXISTS idx_kdc_parent_id ON knowledge_document_chunks(parent_chunk_id);
