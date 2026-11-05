-- Token 估算修正后，同样的预算能装下更多资料，旧缓存里裁剪过的结果不再准确（可随时重建）。可重复执行。
DELETE FROM knowledge_retrieval_cache;
