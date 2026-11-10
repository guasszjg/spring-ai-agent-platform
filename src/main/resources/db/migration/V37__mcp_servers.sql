-- MCP 服务与它提供的工具。可重复执行。
CREATE TABLE IF NOT EXISTS mcp_servers (
    id                    VARCHAR(64) PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    -- 工具名前缀里用的短标识，如 amap → mcp_amap__maps_weather
    slug                  VARCHAR(24)  NOT NULL,
    -- 给人看的地址：查询参数的值已打码（有的服务把 Key 放在地址里）
    url                   VARCHAR(1000) NOT NULL,
    -- 实际连接用的完整地址，加密保存
    url_encrypted         TEXT,
    -- STREAMABLE_HTTP / SSE
    transport             VARCHAR(32)  NOT NULL DEFAULT 'STREAMABLE_HTTP',
    -- NONE / BEARER / HEADERS
    auth_type             VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    -- 加密保存：BEARER 是 Token，HEADERS 是请求头 JSON
    auth_encrypted        TEXT,
    timeout_seconds       INTEGER      NOT NULL DEFAULT 30,
    -- 默认不允许内网地址，管理员对单个服务放行
    allow_private_address BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    last_status           VARCHAR(32),
    last_message          VARCHAR(500),
    last_checked_at       TIMESTAMP,
    remark                VARCHAR(500),
    created_by            VARCHAR(64),
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_servers_slug ON mcp_servers (slug);

CREATE TABLE IF NOT EXISTS mcp_server_tools (
    id              VARCHAR(64) PRIMARY KEY,
    server_id       VARCHAR(64)  NOT NULL,
    -- 服务里的原始工具名
    tool_name       VARCHAR(200) NOT NULL,
    -- 发给模型的函数名，平台内唯一：mcp_<slug>__<工具名>
    function_name   VARCHAR(64)  NOT NULL,
    title           VARCHAR(200),
    description     TEXT,
    input_schema    TEXT,
    -- 名称 + 说明 + 参数格式的指纹，上游改了就要重新审核
    fingerprint     VARCHAR(64),
    -- SEARCH / MAP / DATABASE / FILE / OTHER
    category        VARCHAR(32),
    write_operation BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    -- PENDING 待审核 / APPROVED 已审核 / CHANGED 上游有变化 / REMOVED 上游已删除
    review_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reviewed_by     VARCHAR(64),
    reviewed_at     TIMESTAMP,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_server_tools_function ON mcp_server_tools (function_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_server_tools_server_tool ON mcp_server_tools (server_id, tool_name);
