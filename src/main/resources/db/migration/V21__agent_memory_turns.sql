-- V21: 智能体上下文记忆轮数
-- 每次对话带上最近 N 轮问答（0 = 关闭记忆，每次只发当前问题）。默认 3 轮，与调试页面原先固定带 6 条消息一致。
-- 与其他迁移一样必须可重复执行。
ALTER TABLE agents ADD COLUMN IF NOT EXISTS memory_turns INTEGER NOT NULL DEFAULT 3;
