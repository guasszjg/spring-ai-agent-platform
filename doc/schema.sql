-- ==========================================================
-- Spring AI Agent Platform - Database Schema for PostgreSQL
-- Database: guass_test
-- ==========================================================

-- 1. 用户表 (app_users)
CREATE TABLE IF NOT EXISTS app_users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(200) NOT NULL,
    nickname VARCHAR(100),
    role VARCHAR(64),
    avatar VARCHAR(500),
    ui_preferences TEXT
);

-- 2. 智能体表 (agents)
CREATE TABLE IF NOT EXISTS agents (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(100) UNIQUE,
    avatar VARCHAR(64),
    category VARCHAR(64),
    description TEXT,
    model_name VARCHAR(100),
    system_prompt TEXT,
    temperature DOUBLE PRECISION,
    top_p DOUBLE PRECISION,
    max_tokens INTEGER,
    tools_config TEXT,
    knowledge_base_ids TEXT,
    api_key VARCHAR(128) UNIQUE,
    status VARCHAR(32),
    call_count BIGINT DEFAULT 0,
    avg_response_time_ms DOUBLE PRECISION DEFAULT 0.0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agents_api_key ON agents(api_key);

-- 3. 智能体标签关联表 (agent_tags)
CREATE TABLE IF NOT EXISTS agent_tags (
    agent_id VARCHAR(64) NOT NULL,
    tag VARCHAR(64),
    CONSTRAINT fk_agent_tags FOREIGN KEY (agent_id) REFERENCES agents (id) ON DELETE CASCADE
);

-- 4. 智能体会话表 (agent_conversations)
CREATE TABLE IF NOT EXISTS agent_conversations (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    title VARCHAR(240),
    account VARCHAR(160),
    message_count INTEGER NOT NULL DEFAULT 0,
    user_feedback VARCHAR(32),
    admin_feedback VARCHAR(32),
    last_model VARCHAR(120),
    total_tokens BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversations_agent_id ON agent_conversations(agent_id);
CREATE INDEX IF NOT EXISTS idx_conversations_created_at ON agent_conversations(created_at);

-- 5. 会话消息记录表 (agent_conversation_messages)
CREATE TABLE IF NOT EXISTS agent_conversation_messages (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role VARCHAR(32),
    content TEXT,
    model VARCHAR(120),
    latency_ms BIGINT,
    tokens_used INTEGER,
    created_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON agent_conversation_messages(conversation_id);

-- 6. LLM 模型渠道提供商表 (llm_providers)
CREATE TABLE IF NOT EXISTS llm_providers (
    id VARCHAR(64) PRIMARY KEY,
    vendor VARCHAR(32) NOT NULL,
    protocol VARCHAR(32) DEFAULT 'OPENAI',
    custom_config TEXT,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(500),
    api_key_encrypted TEXT,
    default_model VARCHAR(120),
    models TEXT,
    enabled BOOLEAN DEFAULT FALSE,
    builtin BOOLEAN DEFAULT FALSE,
    timeout_ms INTEGER DEFAULT 30000,
    max_retries INTEGER DEFAULT 1,
    remark VARCHAR(500),
    last_probe_status VARCHAR(32) DEFAULT 'UNTESTED',
    last_probe_message VARCHAR(500),
    last_probe_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 7. 模型网关路由策略表 (gateway_policies)
CREATE TABLE IF NOT EXISTS gateway_policies (
    id VARCHAR(64) PRIMARY KEY,
    default_provider_id VARCHAR(64),
    fallback_provider_id VARCHAR(64),
    failover_enabled BOOLEAN DEFAULT TRUE,
    timeout_ms INTEGER DEFAULT 30000,
    max_retries INTEGER DEFAULT 1,
    updated_at TIMESTAMP
);

-- 8. 智能体每日指标统计表 (agent_daily_stats)
CREATE TABLE IF NOT EXISTS agent_daily_stats (
    id VARCHAR(80) PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    stat_date DATE NOT NULL,
    call_count BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_daily UNIQUE (agent_id, stat_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_stats_agent_date ON agent_daily_stats(agent_id, stat_date);

-- 9. 智能体工具密钥表：密钥仅保存 AES-GCM 密文
CREATE TABLE IF NOT EXISTS agent_tool_secrets (
    agent_id VARCHAR(64) PRIMARY KEY,
    bocha_api_key_encrypted TEXT,
    updated_at TIMESTAMP,
    CONSTRAINT fk_agent_tool_secret_agent FOREIGN KEY (agent_id) REFERENCES agents (id) ON DELETE CASCADE
);

-- 10. 行业场景模板表 (agent_templates)
CREATE TABLE IF NOT EXISTS agent_templates (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(64),
    avatar VARCHAR(64),
    description TEXT,
    model_name VARCHAR(100),
    system_prompt TEXT,
    temperature DOUBLE PRECISION DEFAULT 0.7,
    top_p DOUBLE PRECISION,
    max_tokens INTEGER,
    tags TEXT,
    is_builtin BOOLEAN DEFAULT FALSE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_templates_category ON agent_templates(category);
CREATE INDEX IF NOT EXISTS idx_templates_sort_order ON agent_templates(sort_order);

-- 11. 知识库主表 (knowledge_bases)
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    avatar VARCHAR(64) DEFAULT '📚',
    provider VARCHAR(32) NOT NULL DEFAULT 'DIFY',
    external_dataset_id VARCHAR(128),
    indexing_technique VARCHAR(64) DEFAULT 'high_quality',
    permission VARCHAR(32) DEFAULT 'only_me',
    document_count INTEGER DEFAULT 0,
    word_count BIGINT DEFAULT 0,
    faq_count INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    embedding_model VARCHAR(64) DEFAULT 'text-embedding-v3',
    embedding_provider VARCHAR(64) DEFAULT 'langgenius/tongyi/tongyi',
    search_method VARCHAR(32) DEFAULT 'hybrid_search',
    top_k INTEGER DEFAULT 3,
    rerank_enabled BOOLEAN DEFAULT TRUE,
    rerank_mode VARCHAR(32) DEFAULT 'weighted_score',
    rerank_model VARCHAR(64) DEFAULT 'qwen3-rerank',
    rerank_model_provider VARCHAR(64) DEFAULT 'langgenius/tongyi/tongyi',
    vector_weight DOUBLE PRECISION DEFAULT 0.7,
    keyword_weight DOUBLE PRECISION DEFAULT 0.3,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kb_provider ON knowledge_bases(provider);
CREATE INDEX IF NOT EXISTS idx_kb_ext_dataset_id ON knowledge_bases(external_dataset_id);

-- 12. 知识库文档分块表 (knowledge_documents)
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    external_doc_id VARCHAR(128),
    name VARCHAR(255) NOT NULL,
    extension VARCHAR(32),
    file_size BIGINT DEFAULT 0,
    word_count BIGINT DEFAULT 0,
    token_count BIGINT DEFAULT 0,
    indexing_status VARCHAR(32) DEFAULT 'waiting',
    error_message TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdoc_kb_id ON knowledge_documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kdoc_ext_id ON knowledge_documents(external_doc_id);

-- 13. 知识库问答对表 (knowledge_faqs)
CREATE TABLE IF NOT EXISTS knowledge_faqs (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    external_doc_id VARCHAR(128),
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    category VARCHAR(64) DEFAULT '通用问答',
    content_type VARCHAR(32) DEFAULT 'TEXT',
    image_urls TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    hit_count BIGINT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kfaq_kb_id ON knowledge_faqs(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kfaq_category ON knowledge_faqs(category);

-- ==========================================================
-- 14. 增量变更语句 (如果远程 192 或已有数据库已建过老表，直接执行此段补丁即可)
-- ==========================================================
-- 2026-09-06: agents 表增加 tools_config 字段，只保存非敏感工具配置
ALTER TABLE agents ADD COLUMN IF NOT EXISTS tools_config TEXT;
COMMENT ON COLUMN agents.tools_config IS '智能体非敏感工具配置(JSON格式，包含插件开关、检索条数与时效等，不含API Key)';

CREATE TABLE IF NOT EXISTS agent_tool_secrets (
    agent_id VARCHAR(64) PRIMARY KEY,
    bocha_api_key_encrypted TEXT,
    updated_at TIMESTAMP,
    CONSTRAINT fk_agent_tool_secret_agent FOREIGN KEY (agent_id) REFERENCES agents (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_templates (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(64),
    avatar VARCHAR(64),
    description TEXT,
    model_name VARCHAR(100),
    system_prompt TEXT,
    temperature DOUBLE PRECISION DEFAULT 0.7,
    top_p DOUBLE PRECISION,
    max_tokens INTEGER,
    tags TEXT,
    is_builtin BOOLEAN DEFAULT FALSE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_templates_category ON agent_templates(category);
CREATE INDEX IF NOT EXISTS idx_templates_sort_order ON agent_templates(sort_order);

-- 2026-09-07: 用户界面偏好（跨设备同步卡片/列表等）
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS ui_preferences TEXT;

-- 2026-09-07: 智能体关联知识库列表
ALTER TABLE agents ADD COLUMN IF NOT EXISTS knowledge_base_ids TEXT;
COMMENT ON COLUMN agents.knowledge_base_ids IS '智能体绑定的知识库 ID 列表 (JSON Array)';

-- 2026-09-07: 模型通道支持自定义 HTTP 协议及自定义配置模板
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS protocol VARCHAR(32) DEFAULT 'OPENAI';
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS custom_config TEXT;
COMMENT ON COLUMN llm_providers.protocol IS '通道协议类型: OPENAI / CUSTOM_HTTP';
COMMENT ON COLUMN llm_providers.custom_config IS '第三方非标 HTTP 接口自定义请求头、Body模板与提取路径配置 (JSON)';

-- 2026-09-07: RAG 知识库三张表（如已有老库缺少直接创建，见上方 11、12、13 节）
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    avatar VARCHAR(64) DEFAULT '📚',
    provider VARCHAR(32) NOT NULL DEFAULT 'DIFY',
    external_dataset_id VARCHAR(128),
    indexing_technique VARCHAR(64) DEFAULT 'high_quality',
    permission VARCHAR(32) DEFAULT 'only_me',
    document_count INTEGER DEFAULT 0,
    word_count BIGINT DEFAULT 0,
    faq_count INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    embedding_model VARCHAR(64) DEFAULT 'text-embedding-v3',
    embedding_provider VARCHAR(64) DEFAULT 'langgenius/tongyi/tongyi',
    search_method VARCHAR(32) DEFAULT 'hybrid_search',
    top_k INTEGER DEFAULT 3,
    rerank_enabled BOOLEAN DEFAULT TRUE,
    rerank_mode VARCHAR(32) DEFAULT 'weighted_score',
    rerank_model VARCHAR(64) DEFAULT 'qwen3-rerank',
    rerank_model_provider VARCHAR(64) DEFAULT 'langgenius/tongyi/tongyi',
    vector_weight DOUBLE PRECISION DEFAULT 0.7,
    keyword_weight DOUBLE PRECISION DEFAULT 0.3,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kb_provider ON knowledge_bases(provider);
CREATE INDEX IF NOT EXISTS idx_kb_ext_dataset_id ON knowledge_bases(external_dataset_id);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    external_doc_id VARCHAR(128),
    name VARCHAR(255) NOT NULL,
    extension VARCHAR(32),
    file_size BIGINT DEFAULT 0,
    word_count BIGINT DEFAULT 0,
    token_count BIGINT DEFAULT 0,
    indexing_status VARCHAR(32) DEFAULT 'waiting',
    error_message TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kdoc_kb_id ON knowledge_documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kdoc_ext_id ON knowledge_documents(external_doc_id);

CREATE TABLE IF NOT EXISTS knowledge_faqs (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    external_doc_id VARCHAR(128),
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    category VARCHAR(64) DEFAULT '通用问答',
    content_type VARCHAR(32) DEFAULT 'TEXT',
    image_urls TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    hit_count BIGINT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kfaq_kb_id ON knowledge_faqs(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kfaq_category ON knowledge_faqs(category);
