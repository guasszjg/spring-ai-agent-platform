-- 知识库评测：题目可以标注答案原文（改切分后仍然有效），运行记录保存配置、进度和题目指纹；可重复执行。
ALTER TABLE rag_eval_cases ADD COLUMN IF NOT EXISTS expected_texts TEXT;

ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS label VARCHAR(200);
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS config_json TEXT;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS cases_fingerprint VARCHAR(64);
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'DONE';
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS completed_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS error_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS rerank_fallback_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS error_message VARCHAR(500);
