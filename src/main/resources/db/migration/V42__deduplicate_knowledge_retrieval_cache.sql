-- Keep the newest live result for each cached query, with a fixed-width index for long queries.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY knowledge_base_id, md5(query_text)
        ORDER BY CASE WHEN expires_at IS NULL OR expires_at >= LOCALTIMESTAMP THEN 0 ELSE 1 END,
                 created_at DESC NULLS LAST, id DESC
    ) AS position
    FROM knowledge_retrieval_cache
)
DELETE FROM knowledge_retrieval_cache AS cached
USING ranked
WHERE cached.id = ranked.id AND ranked.position > 1;

CREATE UNIQUE INDEX uq_krc_kb_query ON knowledge_retrieval_cache (knowledge_base_id, (md5(query_text)));
