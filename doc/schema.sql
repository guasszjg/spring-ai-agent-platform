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
    avatar VARCHAR(500)
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
    status VARCHAR(32),
    call_count BIGINT DEFAULT 0,
    avg_response_time_ms DOUBLE PRECISION DEFAULT 0.0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

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

-- ==========================================================
-- 9. 增量变更语句 (如果远程 192 或已有数据库已建过老表，直接执行此段补丁即可)
-- ==========================================================
-- 2026-09-06: agents 表增加 tools_config 字段，用于持久化工具配置与 Bocha Key
ALTER TABLE agents ADD COLUMN IF NOT EXISTS tools_config TEXT;

COMMENT ON COLUMN agents.tools_config IS '智能体工具配置(JSON格式，包含插件开关、Bocha API Key、检索条数与时效等)';

