-- 智能体分享链接（升级计划第五步 5a）：访客通过 /chat/{code} 免登录对话。可重复执行。
CREATE TABLE IF NOT EXISTS agent_share_links (
    id                  VARCHAR(64) PRIMARY KEY,
    agent_id            VARCHAR(64)  NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    access_mode         VARCHAR(16)  NOT NULL,
    access_code_hash    VARCHAR(100),
    expires_at          TIMESTAMP,
    max_messages        INTEGER,
    max_tokens          BIGINT,
    max_visitors        INTEGER,
    visitor_rpm         INTEGER,
    title               VARCHAR(100),
    welcome_message     TEXT,
    example_questions   TEXT,
    show_record_notice  BOOLEAN DEFAULT TRUE,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    api_key_id          VARCHAR(64),
    message_count       BIGINT  NOT NULL DEFAULT 0,
    token_count         BIGINT  NOT NULL DEFAULT 0,
    visitor_count       INTEGER NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMP,
    created_by          VARCHAR(64),
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_share_links_code ON agent_share_links (code);
CREATE INDEX IF NOT EXISTS idx_share_links_agent ON agent_share_links (agent_id);

CREATE TABLE IF NOT EXISTS agent_share_visitors (
    id                VARCHAR(64) PRIMARY KEY,
    link_id           VARCHAR(64) NOT NULL,
    token_hash        VARCHAR(128),
    token_expires_at  TIMESTAMP,
    message_count     BIGINT NOT NULL DEFAULT 0,
    token_count       BIGINT NOT NULL DEFAULT 0,
    last_ip           VARCHAR(64),
    first_seen_at     TIMESTAMP,
    last_seen_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_share_visitors_link ON agent_share_visitors (link_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_share_visitors_token ON agent_share_visitors (token_hash);

-- 平台自动管理的开放凭证（如分享链接的内部对话凭证）：不出现在「开放凭证」列表里，也不能手动修改
ALTER TABLE open_api_keys ADD COLUMN IF NOT EXISTS managed_by VARCHAR(32);
