-- V17: 平台 AI 助手评测运行记录
-- 评测集在 resources/assistant/eval/cases.json；每次运行记录汇总与逐条结果，便于对比提示词、工具或模型调整前后的效果。
-- 与其他迁移一样必须可重复执行。

CREATE TABLE IF NOT EXISTS assistant_eval_runs (
    id                 VARCHAR(64)  PRIMARY KEY,
    status             VARCHAR(16)  NOT NULL,          -- RUNNING / COMPLETED / FAILED
    triggered_by       VARCHAR(64),
    model              VARCHAR(128),
    total              INTEGER      NOT NULL DEFAULT 0,
    completed          INTEGER      NOT NULL DEFAULT 0, -- 已跑完的用例数（进度）
    passed             INTEGER      NOT NULL DEFAULT 0,
    failed             INTEGER      NOT NULL DEFAULT 0,
    skipped            INTEGER      NOT NULL DEFAULT 0,
    errored            INTEGER      NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT       NOT NULL DEFAULT 0,
    completion_tokens  BIGINT       NOT NULL DEFAULT 0,
    duration_ms        BIGINT,
    summary            TEXT,                           -- 按分类汇总（JSON）
    results            TEXT,                           -- 逐条结果（JSON）
    error              TEXT,
    started_at         TIMESTAMP    NOT NULL,
    finished_at        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_asst_eval_started ON assistant_eval_runs (started_at DESC);
