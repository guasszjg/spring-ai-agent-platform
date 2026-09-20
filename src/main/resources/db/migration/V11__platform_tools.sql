CREATE TABLE IF NOT EXISTS platform_tools (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    title VARCHAR(120) NOT NULL,
    prefix VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL DEFAULT 'TIME',
    icon VARCHAR(80),
    icon_class VARCHAR(64),
    custom_icon VARCHAR(32),
    help VARCHAR(500),
    description TEXT,
    config_json TEXT,
    api_key_encrypted TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    builtin BOOLEAN DEFAULT TRUE,
    sort_order INTEGER DEFAULT 100,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_platform_tools_category ON platform_tools(category);
CREATE INDEX IF NOT EXISTS idx_platform_tools_sort ON platform_tools(sort_order);

INSERT INTO platform_tools (
    id, code, name, title, prefix, category, icon, icon_class, custom_icon, help, description, config_json, enabled, builtin, sort_order
) VALUES
(
    'tool-time-convert-timezone',
    'time_convert_timezone',
    '时区转换',
    '时区转换',
    'time',
    'TIME',
    'fa-solid fa-clock',
    'icon-orange',
    NULL,
    '将指定时间在不同时区（如北京、纽约、伦敦等）之间进行转换计算',
    '将指定时间在不同时区之间进行换算转换。例如将北京时间转换为纽约时间、东京时间或伦敦时间。',
    '{"timezone":"Asia/Shanghai","format":"yyyy-MM-dd HH:mm:ss"}',
    TRUE, TRUE, 10
),
(
    'tool-time-timestamp-converter',
    'time_timestamp_converter',
    '时间戳转换',
    '时间戳转换',
    'time',
    'TIME',
    'fa-solid fa-clock',
    'icon-orange',
    NULL,
    '毫秒级/秒级 Unix 时间戳与标准日期时间字符串相互转换',
    'Unix时间戳与格式化时间字符串之间的相互转换。可将秒级/毫秒级时间戳转为日期时间，或将日期时间转为时间戳。',
    '{"timezone":"Asia/Shanghai","format":"yyyy-MM-dd HH:mm:ss"}',
    TRUE, TRUE, 20
),
(
    'tool-time-get-current-time',
    'time_get_current_time',
    '获取当前时间',
    '获取当前时间',
    'time',
    'TIME',
    'fa-solid fa-clock',
    'icon-orange',
    NULL,
    '获取当前系统的精确年月日、时分秒与时区时间',
    '获取指定时区的当前精确日期和时间（包含年月日、时分秒以及星期几）。当用户询问当前时间、现在几点、今天几号等问题时调用。',
    '{"timezone":"Asia/Shanghai","format":"yyyy-MM-dd HH:mm:ss"}',
    TRUE, TRUE, 30
),
(
    'tool-time-date-calculator',
    'time_date_calculator',
    '获取时间戳',
    '获取时间戳',
    'time',
    'TIME',
    'fa-solid fa-clock',
    'icon-orange',
    NULL,
    '计算日期偏移与两个日期相隔天数',
    '计算两个日期之间相隔的天数，或者计算基准日期增加/减少若干天后的新日期。',
    '{"timezone":"Asia/Shanghai","format":"yyyy-MM-dd HH:mm:ss"}',
    TRUE, TRUE, 40
),
(
    'tool-time-calculate-weekday',
    'time_calculate_weekday',
    '星期几计算器',
    '星期几计算器',
    'time',
    'TIME',
    'fa-solid fa-calendar-days',
    'icon-orange',
    NULL,
    '计算历史上或未来的任意特定日期属于星期几',
    '计算历史上或未来的某个具体日期是星期几。当用户询问某一天是周几或星期几时调用。',
    '{"timezone":"Asia/Shanghai","format":"yyyy-MM-dd HH:mm:ss"}',
    TRUE, TRUE, 50
),
(
    'tool-bocha-web-search',
    'bocha_web_search',
    '联网检索',
    'Bocha Web Search',
    'bocha',
    'SEARCH',
    'fa-solid fa-globe',
    'icon-bocha-badge',
    'bocha',
    '博查 AI 搜索引擎，提供全网实时网页、新闻与知识检索',
    '博查 AI 联网搜索引擎。当用户询问最新时事、实时天气、新闻事件、实时数据或任何需要获取最新互联网真实信息的场景时调用（注：系统时钟及今天几号等问题已有系统时间基准保障，无需调用本工具）。',
    '{"count":5,"freshness":"noLimit","summary":true}',
    TRUE, TRUE, 60
)
ON CONFLICT (id) DO NOTHING;
