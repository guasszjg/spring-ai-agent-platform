-- 自研引擎以前没有真正调用 Rerank，却在开关打开时累计调用次数：清零这些虚增的次数并重算预估费用。
-- 有 rerank_tokens 的才是真实调用，不受影响。可重复执行。
UPDATE knowledge_bases
SET rerank_calls = 0,
    estimated_cost = ROUND(((COALESCE(embedding_tokens, 0) + COALESCE(rerank_tokens, 0)) * 0.0000005)::numeric, 4)
WHERE provider = 'SPRING_AI'
  AND COALESCE(rerank_tokens, 0) = 0
  AND COALESCE(rerank_calls, 0) > 0;

-- 权重开始生效、全文检索不再走融合，旧缓存里的排序已经不准（可随时重建）。
DELETE FROM knowledge_retrieval_cache;
