-- V9: 自研 RAG 检索内核 — pgvector / 关键词加速 / 冻结评测集
-- 若 CREATE EXTENSION vector 失败，请在 PostgreSQL 安装 pgvector 后重跑迁移。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE knowledge_document_chunks
    ADD COLUMN IF NOT EXISTS embedding_vector vector,
    ADD COLUMN IF NOT EXISTS embedding_dim integer,
    ADD COLUMN IF NOT EXISTS content_tsv tsvector;

UPDATE knowledge_document_chunks
SET embedding_vector = embedding::vector,
    embedding_dim = json_array_length(embedding::json),
    content_tsv = to_tsvector('simple', coalesce(content, ''))
WHERE embedding IS NOT NULL
  AND btrim(embedding) <> ''
  AND btrim(embedding) <> '[]'
  AND embedding_vector IS NULL;

UPDATE knowledge_document_chunks
SET content_tsv = to_tsvector('simple', coalesce(content, ''))
WHERE content_tsv IS NULL;

CREATE INDEX IF NOT EXISTS idx_kdc_content_trgm
    ON knowledge_document_chunks USING gin (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_kdc_content_tsv
    ON knowledge_document_chunks USING gin (content_tsv);

DO $$
BEGIN
    CREATE INDEX IF NOT EXISTS idx_kdc_embedding_hnsw
        ON knowledge_document_chunks
        USING hnsw (embedding_vector vector_cosine_ops)
        WHERE embedding_vector IS NOT NULL;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'HNSW index skipped (mixed dimensions or pgvector too old): %', SQLERRM;
END
$$;

CREATE OR REPLACE FUNCTION kdc_sync_search_columns() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', coalesce(NEW.content, ''));
    IF NEW.embedding IS NOT NULL AND btrim(NEW.embedding) <> '' AND btrim(NEW.embedding) <> '[]' THEN
        BEGIN
            NEW.embedding_vector := NEW.embedding::vector;
            NEW.embedding_dim := json_array_length(NEW.embedding::json);
        EXCEPTION
            WHEN OTHERS THEN
                NULL;
        END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kdc_sync_search_columns ON knowledge_document_chunks;
CREATE TRIGGER trg_kdc_sync_search_columns
    BEFORE INSERT OR UPDATE OF embedding, content ON knowledge_document_chunks
    FOR EACH ROW
    EXECUTE PROCEDURE kdc_sync_search_columns();

CREATE TABLE IF NOT EXISTS rag_eval_sets (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_sets_kb ON rag_eval_sets (knowledge_base_id);

CREATE TABLE IF NOT EXISTS rag_eval_cases (
    id VARCHAR(64) PRIMARY KEY,
    set_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    query TEXT NOT NULL,
    expected_chunk_ids TEXT,
    expected_document_ids TEXT,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_cases_set ON rag_eval_cases (set_id);
CREATE INDEX IF NOT EXISTS idx_rag_eval_cases_kb ON rag_eval_cases (knowledge_base_id);

CREATE TABLE IF NOT EXISTS rag_eval_runs (
    id VARCHAR(64) PRIMARY KEY,
    set_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    engine VARCHAR(32) NOT NULL,
    case_count INTEGER NOT NULL DEFAULT 0,
    hit_at_5 DOUBLE PRECISION,
    recall_at_10 DOUBLE PRECISION,
    ndcg_at_10 DOUBLE PRECISION,
    avg_latency_ms DOUBLE PRECISION,
    detail_json TEXT,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_runs_kb ON rag_eval_runs (knowledge_base_id);
