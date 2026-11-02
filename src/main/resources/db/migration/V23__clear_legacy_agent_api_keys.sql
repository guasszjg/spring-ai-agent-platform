-- V23: 清除智能体表里残留的明文 Key
-- 早期每个智能体自动生成一个 sk-agent- 明文 Key 存在 agents.api_key，并可直接用于开放调用。
-- 现在开放调用一律使用「开放凭证」（只存哈希）；曾经迁入过的兼容凭证保存的是哈希，不受影响。
-- 与其他迁移一样必须可重复执行。
UPDATE agents SET api_key = NULL WHERE api_key IS NOT NULL;
