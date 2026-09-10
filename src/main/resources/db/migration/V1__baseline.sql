-- ==========================================================
-- Spring AI Agent Platform - Flyway Baseline Migration (V1)
-- Database: PostgreSQL
-- Description: Complete schema baseline covering core agents,
--              knowledge bases, authorization, and open platform
-- ==========================================================

-- 1. 用户表 (app_users)
CREATE TABLE IF NOT EXISTS app_users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(200) NOT NULL,
    nickname VARCHAR(100),
    role VARCHAR(64) DEFAULT 'DEVELOPER',
    status VARCHAR(32) DEFAULT 'ACTIVE',
    auth_version INTEGER DEFAULT 1,
    must_change_password BOOLEAN DEFAULT FALSE,
    temp_password_expires_at TIMESTAMP,
    avatar VARCHAR(500),
    ui_preferences TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 2. 智能体主表 (agents)
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
    status VARCHAR(32),
    call_count BIGINT DEFAULT 0,
    avg_response_time_ms DOUBLE PRECISION DEFAULT 0.0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    tools_config TEXT,
    knowledge_base_ids TEXT,
    api_key VARCHAR(128) UNIQUE,
    owner_id VARCHAR(64),
    owner_username VARCHAR(64),
    is_system BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_agents_api_key ON agents(api_key);
CREATE INDEX IF NOT EXISTS idx_agents_owner_id ON agents(owner_id);
CREATE INDEX IF NOT EXISTS idx_agents_is_system ON agents(is_system);

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

-- 9. 智能体工具密钥表 (agent_tool_secrets)
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
    owner_id VARCHAR(64),
    owner_username VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_templates_category ON agent_templates(category);
CREATE INDEX IF NOT EXISTS idx_templates_sort_order ON agent_templates(sort_order);
CREATE INDEX IF NOT EXISTS idx_agent_templates_owner_id ON agent_templates(owner_id);
CREATE INDEX IF NOT EXISTS idx_agent_templates_is_builtin ON agent_templates(is_builtin);

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
    owner_id VARCHAR(64),
    owner_username VARCHAR(64),
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kb_provider ON knowledge_bases(provider);
CREATE INDEX IF NOT EXISTS idx_kb_ext_dataset_id ON knowledge_bases(external_dataset_id);
CREATE INDEX IF NOT EXISTS idx_kb_owner_id ON knowledge_bases(owner_id);
CREATE INDEX IF NOT EXISTS idx_kb_is_system ON knowledge_bases(is_system);

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

-- 14. 细粒度资源共享授权表 (resource_grants)
CREATE TABLE IF NOT EXISTS resource_grants (
    id VARCHAR(64) PRIMARY KEY,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    grantee_user_id VARCHAR(64) NOT NULL,
    grantee_username VARCHAR(64),
    level VARCHAR(32) NOT NULL,
    granted_by VARCHAR(64),
    created_at TIMESTAMP,
    CONSTRAINT uk_resource_grant UNIQUE (resource_type, resource_id, grantee_user_id)
);

CREATE INDEX IF NOT EXISTS idx_grants_lookup ON resource_grants(resource_type, resource_id, grantee_user_id);
CREATE INDEX IF NOT EXISTS idx_grants_grantee ON resource_grants(grantee_user_id);

-- 15. 开放平台 API 凭证表 (open_api_keys)
CREATE TABLE IF NOT EXISTS open_api_keys (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    issuer_auth_version INTEGER DEFAULT 1,
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL,
    key_hash VARCHAR(128) NOT NULL UNIQUE,
    scopes TEXT NOT NULL,
    agent_scope TEXT,
    ip_allowlist TEXT,
    rate_limit_rpm INTEGER,
    daily_token_quota BIGINT,
    status VARCHAR(16) DEFAULT 'ACTIVE',
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    last_used_at TIMESTAMP,
    last_used_ip VARCHAR(64),
    migrated BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_oak_owner_id ON open_api_keys(owner_id);
CREATE INDEX IF NOT EXISTS idx_oak_key_hash ON open_api_keys(key_hash);
CREATE INDEX IF NOT EXISTS idx_oak_status ON open_api_keys(status);

-- 16. 第三方身份源配置表 (identity_providers)
CREATE TABLE IF NOT EXISTS identity_providers (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    direction VARCHAR(24) NOT NULL DEFAULT 'BIDIRECTIONAL',
    base_url VARCHAR(500),
    auth_type VARCHAR(24) DEFAULT 'BEARER',
    credential_encrypted TEXT,
    timeout_ms INTEGER DEFAULT 8000,
    operations TEXT DEFAULT '{}',
    field_mapping TEXT DEFAULT '{}',
    on_user_created VARCHAR(24) DEFAULT 'OFF',
    on_user_disabled VARCHAR(24) DEFAULT 'OFF',
    fail_policy VARCHAR(24) DEFAULT 'ASYNC',
    webhook_secret_encrypted TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_idp_code ON identity_providers(code);
CREATE INDEX IF NOT EXISTS idx_idp_enabled ON identity_providers(enabled);

-- 17. 外部身份映射绑定表 (external_identities)
CREATE TABLE IF NOT EXISTS external_identities (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    provider_id VARCHAR(64),
    provider_code VARCHAR(64) NOT NULL,
    external_id VARCHAR(191) NOT NULL,
    display_name VARCHAR(191),
    attributes TEXT,
    bind_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(16) DEFAULT 'ACTIVE',
    last_synced_at TIMESTAMP,
    last_sync_error VARCHAR(500),
    bound_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_ext_identity_provider_ext UNIQUE (provider_code, external_id)
);

CREATE INDEX IF NOT EXISTS idx_ext_id_user ON external_identities(user_id);
CREATE INDEX IF NOT EXISTS idx_ext_id_provider ON external_identities(provider_id);

-- 18. 接入终端白名单凭证表 (client_credentials)
CREATE TABLE IF NOT EXISTS client_credentials (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    client_type VARCHAR(32) NOT NULL,
    client_id VARCHAR(191) NOT NULL,
    client_id_hash VARCHAR(128) NOT NULL,
    label VARCHAR(191),
    agent_scope TEXT,
    rate_limit_rpm INTEGER,
    daily_token_quota BIGINT,
    status VARCHAR(16) DEFAULT 'ACTIVE',
    expires_at TIMESTAMP,
    first_seen_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    last_seen_ip VARCHAR(64),
    attributes TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_client_owner_type_hash UNIQUE (owner_id, client_type, client_id_hash)
);

CREATE INDEX IF NOT EXISTS idx_client_cred_owner ON client_credentials(owner_id);
CREATE INDEX IF NOT EXISTS idx_client_cred_hash ON client_credentials(client_id_hash);
CREATE INDEX IF NOT EXISTS idx_client_cred_status ON client_credentials(status);

-- 19. 租户护栏策略表 (guardrail_policies)
CREATE TABLE IF NOT EXISTS guardrail_policies (
    owner_id VARCHAR(64) PRIMARY KEY,
    client_policy VARCHAR(32) DEFAULT 'OFF',
    default_rpm INTEGER DEFAULT 120,
    default_daily_tokens BIGINT,
    max_input_chars INTEGER DEFAULT 8000,
    max_history_turns INTEGER DEFAULT 30,
    pii_mask BOOLEAN DEFAULT FALSE,
    sensitive_words TEXT,
    sensitive_action VARCHAR(16) DEFAULT 'BLOCK',
    prompt_injection VARCHAR(16) DEFAULT 'LOG',
    output_guard BOOLEAN DEFAULT TRUE,
    allowed_hours VARCHAR(64),
    kill_switch BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMP
);

-- 20. 安全审计事件记录表 (audit_events)
CREATE TABLE IF NOT EXISTS audit_events (
    id VARCHAR(64) PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    owner_id VARCHAR(64),
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id VARCHAR(64),
    api_key_id VARCHAR(64),
    client_credential_id VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32),
    resource_id VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    risk_level VARCHAR(8) DEFAULT 'LOW',
    reason_code VARCHAR(64),
    client_ip VARCHAR(64),
    user_agent VARCHAR(255),
    request_id VARCHAR(64),
    sanitized_diff TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_occurred ON audit_events(occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_owner ON audit_events(owner_id);
CREATE INDEX IF NOT EXISTS idx_audit_actor_user ON audit_events(actor_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_events(action);
CREATE INDEX IF NOT EXISTS idx_audit_result ON audit_events(result);

-- ==========================================================
-- 幂等性保障：对于已有数据库历史版本的增量补齐 (IF NOT EXISTS)
-- ==========================================================
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'ACTIVE';
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS auth_version INTEGER DEFAULT 1;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS temp_password_expires_at TIMESTAMP;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS ui_preferences TEXT;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

ALTER TABLE agents ADD COLUMN IF NOT EXISTS tools_config TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS knowledge_base_ids TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
ALTER TABLE agents ADD COLUMN IF NOT EXISTS owner_username VARCHAR(64);
ALTER TABLE agents ADD COLUMN IF NOT EXISTS is_system BOOLEAN DEFAULT FALSE;

ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS protocol VARCHAR(32) DEFAULT 'OPENAI';
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS custom_config TEXT;

ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS owner_username VARCHAR(64);
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS is_system BOOLEAN DEFAULT FALSE;

ALTER TABLE agent_templates ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
ALTER TABLE agent_templates ADD COLUMN IF NOT EXISTS owner_username VARCHAR(64);
