-- V3: RAG 双引擎基线数据模型与版本化索引支持
-- 遵循《Spring-AI自研RAG双引擎设计.md》架构契约

-- 1. 扩充 knowledge_bases 支持主流量索引版本指针与全局分数阈值
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS active_index_version_id VARCHAR(64);
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS source_snapshot_no INTEGER DEFAULT 1;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS score_threshold DOUBLE PRECISION DEFAULT 0.5;
CREATE INDEX IF NOT EXISTS idx_kb_active_version ON knowledge_bases(active_index_version_id);

-- 2. 扩充 knowledge_documents 支持源文件快照哈希与对象存储键
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS current_revision_id VARCHAR(64);
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS object_key VARCHAR(512);
CREATE INDEX IF NOT EXISTS idx_kdoc_sha256 ON knowledge_documents(sha256);

-- 3. 知识库物理索引版本表 (knowledge_index_versions)
CREATE TABLE IF NOT EXISTS knowledge_index_versions (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    engine_type VARCHAR(32) NOT NULL DEFAULT 'DIFY',
    provider_handle VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    parse_config TEXT,
    chunk_config TEXT,
    embedding_model VARCHAR(64) DEFAULT 'text-embedding-v3',
    embedding_provider VARCHAR(64) DEFAULT 'langgenius/tongyi/tongyi',
    dimension INTEGER DEFAULT 1024,
    distance_metric VARCHAR(32) DEFAULT 'COSINE',
    rerank_model VARCHAR(64) DEFAULT 'qwen3-rerank',
    search_method VARCHAR(32) DEFAULT 'hybrid_search',
    top_k INTEGER DEFAULT 3,
    score_threshold DOUBLE PRECISION DEFAULT 0.5,
    vector_weight DOUBLE PRECISION DEFAULT 0.7,
    keyword_weight DOUBLE PRECISION DEFAULT 0.3,
    source_snapshot_no INTEGER DEFAULT 1,
    config_hash VARCHAR(64),
    total_documents INTEGER DEFAULT 0,
    total_chunks INTEGER DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_kiv_kb_version UNIQUE (knowledge_base_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_kiv_kb_id ON knowledge_index_versions(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kiv_status ON knowledge_index_versions(status);
CREATE INDEX IF NOT EXISTS idx_kiv_engine ON knowledge_index_versions(engine_type);

-- 4. 知识库源文件快照表 (knowledge_source_revisions)
CREATE TABLE IF NOT EXISTS knowledge_source_revisions (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    revision_no INTEGER NOT NULL DEFAULT 1,
    file_name VARCHAR(255) NOT NULL,
    extension VARCHAR(32),
    mime_type VARCHAR(128),
    size_bytes BIGINT DEFAULT 0,
    sha256 VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    storage_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    created_at TIMESTAMP,
    CONSTRAINT uk_ksr_doc_revision UNIQUE (document_id, revision_no)
);

CREATE INDEX IF NOT EXISTS idx_ksr_kb_id ON knowledge_source_revisions(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_ksr_doc_id ON knowledge_source_revisions(document_id);
CREATE INDEX IF NOT EXISTS idx_ksr_sha256 ON knowledge_source_revisions(sha256);

-- 5. 知识库异步索引任务表 (knowledge_index_jobs)
CREATE TABLE IF NOT EXISTS knowledge_index_jobs (
    id VARCHAR(64) PRIMARY KEY,
    index_version_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    job_stage VARCHAR(32) NOT NULL DEFAULT 'PARSE',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP,
    attempt INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    checkpoint_data TEXT,
    processed_documents INTEGER DEFAULT 0,
    total_documents INTEGER DEFAULT 0,
    processed_chunks INTEGER DEFAULT 0,
    total_chunks INTEGER DEFAULT 0,
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    next_retry_at TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kij_version_id ON knowledge_index_jobs(index_version_id);
CREATE INDEX IF NOT EXISTS idx_kij_kb_id ON knowledge_index_jobs(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kij_status ON knowledge_index_jobs(status);
CREATE INDEX IF NOT EXISTS idx_kij_lease_until ON knowledge_index_jobs(lease_until);
