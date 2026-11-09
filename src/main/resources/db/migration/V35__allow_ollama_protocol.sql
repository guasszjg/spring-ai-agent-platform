-- 早期由 ddl-auto 建表时，Hibernate 按当时的枚举值生成了 protocol 检查约束（只有 OPENAI / CUSTOM_HTTP），
-- 新增 OLLAMA 后保存会违反约束。改为包含全部协议类型。可重复执行。
ALTER TABLE llm_providers DROP CONSTRAINT IF EXISTS llm_providers_protocol_check;
ALTER TABLE llm_providers ADD CONSTRAINT llm_providers_protocol_check
    CHECK (protocol IS NULL OR protocol IN ('OPENAI', 'OLLAMA', 'CUSTOM_HTTP'));
