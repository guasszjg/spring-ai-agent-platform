-- 新增智谱 GLM 通道（vendor = ZHIPU）。
-- 早期由 Hibernate ddl-auto 建表的库会带一个枚举值 CHECK 约束（只允许旧的几个厂商），
-- 去掉它，厂商取值由应用层枚举约束；可重复执行。
ALTER TABLE llm_providers DROP CONSTRAINT IF EXISTS llm_providers_vendor_check;
