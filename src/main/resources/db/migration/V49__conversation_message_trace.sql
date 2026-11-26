-- 为智能体会话消息表增加调用链路详情 JSON（用于日志回溯分析各阶段耗时与节点详情）
ALTER TABLE agent_conversation_messages ADD COLUMN IF NOT EXISTS trace_json TEXT;
