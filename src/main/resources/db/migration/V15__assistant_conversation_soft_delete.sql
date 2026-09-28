-- V15: 助手会话改为软删除
-- 用户删除会话时立即清空消息内容，但保留消息行上的 token 用量，保证概览页的用量与成本统计不因删除会话而减少；
-- 这些行由数据保留清理任务按 app.assistant.retention-days 物理删除。
ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
