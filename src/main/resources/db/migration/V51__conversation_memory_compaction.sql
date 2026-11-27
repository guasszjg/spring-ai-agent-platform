-- 智能体可选「记忆压缩」开关（默认关闭），以及智能体/AI 助手会话的滚动记忆摘要字段
ALTER TABLE agents ADD COLUMN IF NOT EXISTS memory_compression BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_conversations ADD COLUMN IF NOT EXISTS memory_summary TEXT;
ALTER TABLE agent_conversations ADD COLUMN IF NOT EXISTS summary_msg_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS memory_summary TEXT;
ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS summary_msg_count INTEGER NOT NULL DEFAULT 0;
