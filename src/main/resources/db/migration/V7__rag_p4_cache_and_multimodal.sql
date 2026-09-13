-- V7: RAG P4 语义缓存、图文跨模态与 GraphRAG 知识图谱试点支持
-- 遵循《Spring-AI自研RAG双引擎设计.md》第 1.3 节、第 10 章与第 18 章

-- 1. 语义缓存表 (Knowledge Retrieval Semantic Cache)
CREATE TABLE IF NOT EXISTS knowledge_retrieval_cache (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    index_version_id VARCHAR(64),
    query_text TEXT NOT NULL,
    query_vector TEXT,
    result_json TEXT NOT NULL,
    hit_count BIGINT DEFAULT 1,
    latency_saved_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_krc_kb_id ON knowledge_retrieval_cache(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_krc_expires ON knowledge_retrieval_cache(expires_at);

-- 2. 物理分段支持多模态与图文元数据 (Chapter 18)
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS image_url VARCHAR(512);
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS image_caption TEXT;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS image_width INTEGER;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS image_height INTEGER;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS matched_by VARCHAR(32);

-- 3. GraphRAG 实体关系三元组存储 (Knowledge Graph Triplets)
CREATE TABLE IF NOT EXISTS knowledge_graph_triplets (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    source_entity VARCHAR(128) NOT NULL,
    relation VARCHAR(128) NOT NULL,
    target_entity VARCHAR(128) NOT NULL,
    source_chunk_id VARCHAR(64),
    weight DOUBLE PRECISION DEFAULT 1.0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kgt_kb_id ON knowledge_graph_triplets(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kgt_source ON knowledge_graph_triplets(source_entity);
CREATE INDEX IF NOT EXISTS idx_kgt_target ON knowledge_graph_triplets(target_entity);
