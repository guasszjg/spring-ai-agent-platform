-- 切片的上下文标题：「文档名 › 章 › 节」，只用于向量化和关键词检索，回答时引用的仍是切片正文。可重复执行。
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS context_title VARCHAR(300);
