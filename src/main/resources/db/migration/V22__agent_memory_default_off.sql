-- V22: 上下文记忆默认关闭
-- 新建智能体默认 0 轮（每次只发当前问题，最省 token），需要追问的智能体再手动打开；已有智能体统一改为 0。
-- 与其他迁移一样必须可重复执行。
ALTER TABLE agents ALTER COLUMN memory_turns SET DEFAULT 0;
UPDATE agents SET memory_turns = 0 WHERE memory_turns <> 0;
