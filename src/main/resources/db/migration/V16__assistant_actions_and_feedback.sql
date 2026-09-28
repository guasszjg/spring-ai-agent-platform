-- V16: 平台 AI 助手（P2）——待确认操作与回复反馈
-- 写操作不直接执行：模型调用写工具只生成一条 PENDING 记录并在对话中渲染操作卡片，用户确认后才执行。
-- 与其他迁移一样必须可重复执行。

CREATE TABLE IF NOT EXISTS assistant_actions (
    id               VARCHAR(64)  PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    message_id       VARCHAR(64),                    -- 生成该操作的助手回复，回复落库后回填
    user_id          VARCHAR(64)  NOT NULL,
    tool_name        VARCHAR(64)  NOT NULL,
    risk_level       VARCHAR(8)   NOT NULL,          -- W1 新建 / W2 修改
    title            VARCHAR(200) NOT NULL,
    payload          TEXT         NOT NULL,          -- 执行参数（JSON），确认时只使用这里保存的参数
    preview          TEXT,                           -- 卡片展示内容（JSON）：字段、修改前后对比
    status           VARCHAR(16)  NOT NULL,          -- PENDING / EXECUTING / EXECUTED / FAILED / CANCELLED / EXPIRED
    result           TEXT,                           -- 执行结果或失败原因（JSON）
    resource_type    VARCHAR(32),
    resource_id      VARCHAR(64),
    expires_at       TIMESTAMP    NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    executed_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_asst_action_conv ON assistant_actions (conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_asst_action_user ON assistant_actions (user_id, created_at DESC);

-- 回复下方的"有用 / 没用"反馈
ALTER TABLE assistant_messages ADD COLUMN IF NOT EXISTS feedback VARCHAR(8);
ALTER TABLE assistant_messages ADD COLUMN IF NOT EXISTS feedback_at TIMESTAMP;
