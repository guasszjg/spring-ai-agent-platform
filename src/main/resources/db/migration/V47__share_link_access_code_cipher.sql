-- 分享链接访问码额外加密保存一份，创建人可以随时在控制台查看。
-- 访客校验仍然只用 access_code_hash；本迁移之前创建的链接没有密文，修改一次访问码后才能查看。
ALTER TABLE agent_share_links ADD COLUMN IF NOT EXISTS access_code_cipher VARCHAR(255);
