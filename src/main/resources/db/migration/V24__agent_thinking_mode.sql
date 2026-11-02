-- V24: 智能体思考模式
-- AUTO = 跟随模型默认（不传思考参数，与此前线上行为一致）；ON = 显式开启；OFF = 显式关闭。
-- 与其他迁移一样必须可重复执行。
ALTER TABLE agents ADD COLUMN IF NOT EXISTS thinking_mode VARCHAR(8) NOT NULL DEFAULT 'AUTO';
