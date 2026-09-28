-- V14: 平台 AI 助手（P1）会话持久化
-- 设计见 docs/平台AI助手（内置智能体）设计与规划.md 第 8 节；待确认操作表 assistant_actions 属于 P2，不在本迁移中。
-- 与其他迁移一样必须可重复执行（无迁移历史的旧库会以 0 为基线重放全部迁移）。

CREATE TABLE IF NOT EXISTS assistant_conversations (
    id          VARCHAR(64)  PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    title       VARCHAR(200),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_asst_conv_user ON assistant_conversations (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS assistant_messages (
    id                 VARCHAR(64)  PRIMARY KEY,
    conversation_id    VARCHAR(64)  NOT NULL REFERENCES assistant_conversations(id) ON DELETE CASCADE,
    user_id            VARCHAR(64)  NOT NULL,
    role               VARCHAR(16)  NOT NULL,          -- user / assistant
    content            TEXT,
    mode               VARCHAR(16),                    -- CHAT / AGENT
    tool_calls         TEXT,                           -- 本轮调用过的工具（JSON 数组：name / label / ok）
    model              VARCHAR(128),
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    latency_ms         BIGINT,
    degraded           BOOLEAN,
    created_at         TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_asst_msg_conv ON assistant_messages (conversation_id, created_at);
-- 用量统计按用户和日期聚合助手 token
CREATE INDEX IF NOT EXISTS idx_asst_msg_user_time ON assistant_messages (user_id, created_at);
