-- V12: 修复 V9 的 HNSW 索引
--
-- 问题：V9 把 HNSW 直接建在 embedding_vector 上，而该列类型是无维度的 `vector`。
--       pgvector 要求 HNSW 索引列有固定维度，因此建索引必然失败，并被 EXCEPTION 块静默吞掉，
--       向量检索一直在做顺序扫描。
-- 方案：保留无维度列（允许不同维度的 Embedding 模型共存），为每个维度建"表达式 + 部分"索引：
--         USING hnsw ((embedding_vector::vector(N)) vector_cosine_ops) WHERE embedding_dim = N
--       查询端（PgChunkSearch）使用完全相同的表达式与谓词。
--       运行期出现的新维度由 VectorIndexManager 以 CREATE INDEX CONCURRENTLY 自动补建。
-- 限制：pgvector 的 vector 类型 HNSW 最多 2000 维，超过的维度不建索引（顺序扫描）。

DROP INDEX IF EXISTS idx_kdc_embedding_hnsw;

-- HNSW 构建对内存敏感，默认 64MB 时大表构建会明显变慢（仅作用于本迁移事务）
SET LOCAL maintenance_work_mem = '256MB';

DO $$
DECLARE
    d integer;
    dims integer[];
BEGIN
    -- 先把维度收集到数组：若直接 FOR ... IN SELECT 遍历本表，游标未关闭时不能对同一张表 CREATE INDEX
    SELECT array_agg(x ORDER BY x) INTO dims FROM (
        SELECT DISTINCT embedding_dim AS x
        FROM knowledge_document_chunks
        WHERE embedding_dim BETWEEN 1 AND 2000
          AND embedding_vector IS NOT NULL
        UNION
        SELECT 1024
    ) t;

    FOREACH d IN ARRAY dims
    LOOP
        BEGIN
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON knowledge_document_chunks '
                    || 'USING hnsw ((embedding_vector::vector(%s)) vector_cosine_ops) '
                    || 'WHERE embedding_dim = %s AND embedding_vector IS NOT NULL',
                'idx_kdc_hnsw_d' || d, d, d);
            RAISE NOTICE 'HNSW index idx_kdc_hnsw_d% ready', d;
        EXCEPTION
            WHEN OTHERS THEN
                -- 不阻断迁移；应用启动后 VectorIndexManager 会再次尝试并打印 WARN 日志
                RAISE WARNING 'HNSW index for dim % not created: %', d, SQLERRM;
        END;
    END LOOP;
END
$$;

-- 更新统计信息：小知识库规划器会走 idx_kdc_kb_id 精确排序，大知识库走 HNSW
ANALYZE knowledge_document_chunks;
