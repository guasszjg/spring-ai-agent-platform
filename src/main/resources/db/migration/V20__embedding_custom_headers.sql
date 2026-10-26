-- V20: 向量模型支持自定义请求头鉴权
-- 部分中转服务用自定义 Header（如 app_id / device_mac）代替 Authorization: Bearer 鉴权；请求头整体加密后存储。
-- 与其他迁移一样必须可重复执行。
ALTER TABLE embedding_configs ADD COLUMN IF NOT EXISTS custom_headers_encrypted TEXT;
