-- V13: 让迁移脚本建出的表结构与 JPA 实体一致
--
-- 背景：Spring Boot 4 需要 spring-boot-starter-flyway 才会自动执行迁移，此前一直缺失，
--       各环境的表实际由 Hibernate ddl-auto=update 按实体建出，迁移脚本与实体逐渐分叉。
--       用纯迁移脚本建出的新库在 ddl-auto=validate（prod）下无法启动。
-- 原则：以实体（即现有环境中数据所在的列）为准，只补不改名；全部可重复执行。

-- 1. 实体字段 topP / topK 经命名策略映射为 topp / topk（末尾单个大写字母不加下划线），
--    而 V1/V3 建的是 top_p / top_k。现有环境的数据都在 topp / topk。
ALTER TABLE agents ADD COLUMN IF NOT EXISTS topp DOUBLE PRECISION;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS topk INTEGER;

-- 2. 实体已有、迁移中遗漏的列
ALTER TABLE open_api_keys ADD COLUMN IF NOT EXISTS grace_expires_at TIMESTAMP;
ALTER TABLE open_api_keys ADD COLUMN IF NOT EXISTS rotated_to_key_id VARCHAR(64);

ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS hit_at5 DOUBLE PRECISION;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS recall_at10 DOUBLE PRECISION;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS ndcg_at10 DOUBLE PRECISION;

-- 3. 告警规则表（AlertRule 实体）
CREATE TABLE IF NOT EXISTS alert_rules (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    metric VARCHAR(64) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    time_window_minutes INTEGER NOT NULL,
    webhook_url VARCHAR(500),
    webhook_secret VARCHAR(128),
    enabled BOOLEAN NOT NULL,
    silence_minutes INTEGER NOT NULL,
    last_triggered_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 4. 列类型与实体不一致：Double → double precision，LocalDateTime → timestamp（无时区）
--    仅在类型不同时执行，避免对已一致的环境做无意义的表重写。
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('knowledge_bases',          'estimated_cost', 'double precision',            'DOUBLE PRECISION'),
            ('knowledge_graph_triplets', 'created_at',     'timestamp without time zone', 'TIMESTAMP'),
            ('knowledge_retrieval_cache','created_at',     'timestamp without time zone', 'TIMESTAMP'),
            ('knowledge_retrieval_cache','expires_at',     'timestamp without time zone', 'TIMESTAMP')
        ) AS t(tbl, col, want, ddl)
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = r.tbl
              AND column_name = r.col AND data_type <> r.want
        ) THEN
            EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE %s', r.tbl, r.col, r.ddl);
        END IF;
    END LOOP;
END
$$;
