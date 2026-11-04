-- 清空检索语义缓存（可随时重建）：旧缓存读回时会因多出的 image/graph 字段解析失败、被当成空结果，
-- 且不含 FAQ 标识（回答取不到 FAQ 附图）。可重复执行。
DELETE FROM knowledge_retrieval_cache;
