-- Skill 技能库：把某类问题的处理步骤写成技能，挂到智能体上按需加载。可重复执行。
CREATE TABLE IF NOT EXISTS skills (
    id             VARCHAR(64) PRIMARY KEY,
    -- 技能标识，模型调用 load_skill 时传这个名字，平台内唯一
    name           VARCHAR(64)  NOT NULL,
    -- 什么时候用：对话时列在系统提示里，模型据此判断要不要加载
    description    TEXT         NOT NULL,
    -- 详细步骤（Markdown），模型调用 load_skill 后才读到
    content        TEXT         NOT NULL,
    -- 需要的工具（函数名 JSON 数组），智能体没启用时给出提示
    required_tools TEXT,
    owner_id       VARCHAR(64),
    owner_username VARCHAR(64),
    is_system      BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    version        INTEGER      NOT NULL DEFAULT 1,
    load_count     BIGINT       NOT NULL DEFAULT 0,
    last_loaded_at TIMESTAMP,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skills_name ON skills (name);

-- 每次修改描述、步骤或需要的工具都留一份，可以回看和恢复
CREATE TABLE IF NOT EXISTS skill_versions (
    id             VARCHAR(64) PRIMARY KEY,
    skill_id       VARCHAR(64)  NOT NULL,
    version        INTEGER      NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    description    TEXT         NOT NULL,
    content        TEXT         NOT NULL,
    required_tools TEXT,
    -- CREATE 新建 / EDIT 编辑 / IMPORT 导入 / RESTORE 恢复
    source         VARCHAR(16),
    note           VARCHAR(200),
    created_by     VARCHAR(64),
    created_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_skill_versions_skill ON skill_versions (skill_id, version);

-- 智能体关联的技能，和知识库一样存成 JSON 数组
ALTER TABLE agents ADD COLUMN IF NOT EXISTS skill_ids TEXT;
