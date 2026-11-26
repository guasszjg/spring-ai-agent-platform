-- 为知识库 FAQ 增加视频附件地址列表（JSON Array，支持本地上传 /uploads/knowledge/ 或外部 http(s) 视频链接）
ALTER TABLE knowledge_faqs ADD COLUMN IF NOT EXISTS video_urls TEXT;
