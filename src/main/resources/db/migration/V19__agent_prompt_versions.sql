-- V19: 智能体系统提示词版本管理
-- 每次系统提示词发生变化（创建、调试台发布、编辑、回滚、AI 助手修改）记录一个版本，可在调试台查看历史、对比与回滚。
-- 与其他迁移一样必须可重复执行。

CREATE TABLE IF NOT EXISTS agent_prompt_versions (
    id               VARCHAR(64)  PRIMARY KEY,
    agent_id         VARCHAR(64)  NOT NULL,
    version_no       INTEGER      NOT NULL,          -- 每个智能体从 1 开始递增
    system_prompt    TEXT,
    note             VARCHAR(200),                   -- 版本说明
    source           VARCHAR(32)  NOT NULL,          -- INITIAL / CREATE / COPY / PUBLISH / EDIT / ROLLBACK / ASSISTANT
    created_by       VARCHAR(64),
    created_by_name  VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_prompt_versions_no ON agent_prompt_versions (agent_id, version_no);
