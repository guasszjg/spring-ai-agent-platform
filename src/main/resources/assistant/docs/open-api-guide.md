# 开放接口接入指南

给对接方看：广告机等终端、后台服务，或任何通过 API 调用平台的外部系统。接口字段的完整说明以控制台「开放与安全 → 接入文档」为准（含在线调试）。

## 1. 接入流程概览

1. 在「开放与安全 → 开放凭证」签发凭证，勾选所需权限（scope）与智能体范围。明文 Key 只在签发时显示一次。
2. （可选）在「接入终端」登记终端，并在「护栏策略」设置终端管控模式。
3. 调用接口：请求头 `Authorization: Bearer <Key>`，基础地址 `https://<平台域名>/open/v1`。
也可在目标智能体调试页「访问 API」点击「重新生成」，直接签发只限该智能体、只有 `chat` 权限的专属 Key。

## 2. 凭证：权限与范围

凭证格式为 `sk-live-…`，平台只保存哈希。一把 Key 能做什么由**权限（scope）**和**智能体范围**决定：
- `chat`：对话
- `agents:read` / `agents:write` / `agents:bind_kb`：查询、增改删/发布暂停智能体、绑定知识库
- `kb:read` / `kb:write`：查询/检索知识库与文档/FAQ、增改删知识库/文档/FAQ
- `conversations:read`：查询会话与消息、删除会话
- `usage:read`：用量统计、审计事件
- `account:read` / `account:write`：账号信息、外部身份绑定

**轮换与失效**：轮换后新 Key 立即生效，旧 Key 有 24 小时宽限期（响应带 `X-Key-Status: ROTATING` 和 `X-Key-Grace-Expires`）。所属账号停用或签发人权限变更后，Key 自动失效。凭证可设过期时间和 IP 白名单。

## 3. 对话接口

请求 `POST /open/v1/chat-messages`，Body 字段：
- `agent_id`：智能体 ID 或业务编码（凭证仅限单个智能体时可省略）
- `message`：用户问题（兼容 `query` 别名）
- `response_mode`：`blocking`（完整 JSON）或 `streaming`（SSE，默认）
- `conversation_id`：续接多轮对话时传上一轮返回的会话 ID
- `user` / `end_user`：终端用户标识，同一会话必须保持一致
- `inputs`（可选，兼容 `variables`）：自定义提示词变量键值对（如 `{"store_name":"深圳南山店"}`），自动替换系统提示词中的 `{{store_name}}`；不传时自动回退默认值，兼容已有调用方

阻塞模式返回 `conversation_id`、`message_id`、`reply`、`images`、`videos`、`model`、`tool_called`、`tokens_used`、`latency_ms`、`request_id`；流式模式事件为 `message`、`message_end`（含 `images`、`videos` 与 `metadata`）、`guardrail`、`error`。自定义 HTTP 协议通道不支持流式，会一次性推送。中途停止直接断开连接即可（`POST /open/v1/chat-messages/{taskId}/stop` 返回 `not_supported`）。

**FAQ 回答附图（`images`）与视频（`videos`）**：命中带图片或视频的 FAQ 且相似度达到阈值时，`images`（最多 9 张）和 `videos`（最多 3 个）分别返回最高分 FAQ 的完整媒体 URL 数组（站内文件自动补全根地址，外部 `http(s)://` 链接原样保留）；域名经反向代理对外时需配置 `app.public-base-url`（环境变量 `APP_PUBLIC_BASE_URL`）。

**后台调用链路排查**：平台管理员在「构建 → 工具」临时开启「记录开放接口调用链路」后，服务端会静默记录客户端调用的各阶段耗时到智能体「日志」中，**不改动客户端请求参数与返回体结构**（也不会向客户端或 `/open/v1/conversations/{id}/messages` 暴露内部链路细节），默认关闭以保持零额外开销。

## 4. 接入终端（`X-Client-Id`）

终端管控模式在「护栏策略」设置：关闭（默认）、只记录、强制（未带或未登记拒绝 `client_required` / `client_not_allowed`）、强制 + 自动登记（自动登记待审批）。请求头支持 `X-Client-Type`（`SN` / `MAC` / `IMEI` / `APP_ID` / `CUSTOM_KEY`）、`X-Client-Id`、`X-End-User`。终端可单独设 RPM 与每日 Token 配额。

## 5. 多轮对话、记忆与思考模式

智能体 `memoryTurns`（0～10 轮，默认 0 关闭）控制是否带历史问答，实际轮数不超过护栏策略「最大历史轮数」。`thinkingMode` 默认 `OFF`，可选 `AUTO` / `ON`。

## 6. 限流、配额与幂等

- **限流**：每分钟请求数（RPM），优先用凭证设置，其次护栏策略默认 RPM，兜底 120。超出返回 `429 rate_limited`（带 `Retry-After` 和 `X-RateLimit-*` 头）。
- **配额**：开发者、凭证、终端任一超出每日 Token 上限返回 `429 quota_exceeded`。
- **幂等**：写操作带 `Idempotency-Key`，24 小时内重复请求直接回放首次结果（`X-Idempotent-Replay: true`）。

## 7. 接口清单

基础地址 `/open/v1`：
- 对话与状态：`POST /chat-messages`、`GET /status`
- 智能体：`GET/POST /agents`、`GET/PUT/DELETE /agents/{id}`、`POST /agents/{id}/publish|pause`、`GET/PUT /agents/{id}/knowledge-bases`
- 知识库：`GET/POST /knowledge-bases`、`GET/PUT/DELETE /knowledge-bases/{id}`、`GET/POST /knowledge-bases/{id}/documents`、`DELETE …/documents/{docId}`、`GET/POST/PUT/DELETE …/faqs`、`POST /knowledge-bases/{id}/retrieve`
- 会话：`GET /conversations`、`GET /conversations/{id}/messages`、`DELETE /conversations/{id}`
- 用量与审计：`GET /usage/summary`、`GET /usage/daily`、`GET /audit/events`
- 账号对接：`GET /account`、`GET/POST/DELETE /account/identities`、`POST /webhooks/identity/{providerCode}`

早期 `/api/v1/chat-messages` 仅作兼容保留（带 `Deprecation: true`），新接入请使用 `/open/v1/chat-messages`。
