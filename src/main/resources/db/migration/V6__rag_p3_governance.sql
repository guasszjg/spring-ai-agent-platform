-- V6: RAG P3 治理增强、成本归集与多格式解析支持
-- 遵循《Spring-AI自研RAG双引擎设计.md》第 1.3 节与第 10 章

ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS embedding_tokens BIGINT DEFAULT 0;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS retrieval_tokens BIGINT DEFAULT 0;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS rerank_calls BIGINT DEFAULT 0;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS estimated_cost NUMERIC(10, 4) DEFAULT 0.0000;

ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS file_format VARCHAR(32);
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS parser_type VARCHAR(32) DEFAULT 'DIRECT';
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS is_scanned BOOLEAN DEFAULT false;
