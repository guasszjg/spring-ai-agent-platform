-- 新建智能体默认关闭思考（首字更快、更省 token）。只改列默认值，已有智能体的设置不变。可重复执行。
ALTER TABLE agents ALTER COLUMN thinking_mode SET DEFAULT 'OFF';
