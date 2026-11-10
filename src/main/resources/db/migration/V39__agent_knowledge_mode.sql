-- 智能体使用知识库的方式：PRE_RETRIEVE 先检索后回答（默认，空值同此）/ AGENTIC 模型自主检索。可重复执行。
ALTER TABLE agents ADD COLUMN IF NOT EXISTS knowledge_mode VARCHAR(16);
