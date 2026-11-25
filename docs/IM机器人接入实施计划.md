# IM 机器人接入实施计划（飞书 / 钉钉 / 企业微信 / QQ）

属于《AgentMatrix 升级计划》第五步「智能体访问入口」的 5c。现在「访问入口」有 Web 应用（分享链接）和后端服务 API 两种，本计划加第三种「IM 机器人」：把智能体接到飞书、钉钉、企业微信和 QQ 里，用户在聊天软件里直接和智能体对话。

状态：计划（2026-11-25 写），未开工。

## 1. 目标与范围

### 1.1 已确认的决定（2026-11-25）

1. **四个平台都做**：飞书、钉钉、企业微信、QQ，按第 9 节的顺序分步交付。
2. **使用对象**：公司内部员工和外部用户都会用。
3. **部署环境**：支持外网，也能提供公网 HTTPS 地址。所以除了长连接，HTTP 回调方式也可以用（QQ 必须用回调）。

### 1.2 本期范围

- 私聊：用户给机器人发消息，智能体回复。
- 群聊：只在用户 @ 机器人时回复。
- 多轮对话：按用户（群里按「群 + 用户」）分开保存，`/new` 开始新会话。
- 能流式显示的平台（飞书、钉钉、企业微信）边生成边更新，QQ 整段回复。
- 收到的消息只处理文字。图片、文件、语音回复一句「暂时只支持文字消息」。
- 控制台：配置向导、测试连接、连接状态、用量、对话记录、停用 / 启用 / 删除。

### 1.3 不做

- **个人微信**：没有开放接口。腾讯 2026 年 3 月发布的 ClawBot 插件是给个人接自己的 AI 助手用的，一次只能绑一个微信号，不适合平台化。第三方桥接工具大多是逆向协议，有封号和合规风险。
- **微信公众号**：被动回复必须 5 秒内返回，大模型来不及；只能先回「正在思考」，再用认证服务号的客服消息补发，体验一般。外部微信用户的需求以后考虑通过企业微信「微信客服」实现，单独评估。
- 机器人主动推送消息（定时提醒、通知）：QQ 已经关闭主动推送，其他平台也不在本期范围。
- 消息卡片上的按钮交互、在 IM 里执行写操作。

## 2. 平台能力（2026-11 核实）

| | 飞书 | 钉钉 | 企业微信 | QQ |
|---|---|---|---|---|
| 机器人类型 | 自建应用 + 机器人能力 | 企业内部应用机器人 | 智能机器人（AI Bot） | QQ 开放平台机器人 |
| 接收消息 | 长连接（推荐）或 HTTP 回调 | Stream 模式（WebSocket 长连接）或 HTTP 回调 | WebSocket 长连接 | HTTP 回调（需要公网 HTTPS） |
| 流式回复 | 流式更新卡片 | AI 卡片流式更新 | 内置流式回复协议 | 不支持，整段回复 |
| 凭证 | App ID、App Secret（回调方式另需 Encrypt Key、Verification Token） | Client ID、Client Secret（原 AppKey / AppSecret） | Bot ID、Secret | AppID、AppSecret |
| 官方 Java SDK | 有（`oapi-sdk-java`，含长连接） | 有（`app-stream-client`） | 需核实，没有就按协议自己实现 | 没有，按协议自己实现 |
| 限制 | 应用要发布并设置可用范围 | 应用要发布并设置可用范围 | 机器人在企业微信后台创建 | 上线要平台审核；只能被动回复（2025-04 起关闭主动推送）；群里必须 @ |

资料来源：[飞书机器人概述](https://open.larkoffice.com/document/client-docs/bot-v3/bot-overview)、[飞书 Java SDK](https://github.com/larksuite/oapi-sdk-java)、[钉钉 Stream SDK 说明](https://www.pypi.org/project/dingtalk-stream/0.24.3/)、[企业微信智能机器人 SDK 说明](https://pypi.org/project/wecom-aibot-sdk-python/0.1.5/)、[QQ 机器人 HTTP 回调](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/webhook.html)、[QQ 关闭主动消息的讨论](https://github.com/zeroclaw-labs/zeroclaw/issues/1549)。各平台开工前再对一遍官方文档，尤其是企业微信智能机器人的长连接协议和 QQ 的签名方式。

## 3. 总体设计

### 3.1 思路：照分享链接的模式做

分享链接（5a）已经解决了「没有平台账号的人用智能体」的问题：每个链接一把内部凭证（`open_api_keys.managed_by='SHARE_LINK'`）、访客账号、用量上限、审计、对话记录。IM 渠道照同样的模式：

- 每个渠道一把内部凭证，`managed_by='IM_CHANNEL'`，不出现在凭证列表里，`requireOwned` 拒绝操作（和分享链接一致）。
- 对话走开放接口的同一条链路，模型调用、知识库、工具、护栏、限流、计费、审计都不重写。
- 账号记为 `im:{渠道ID}:{会话键}`，对话记录照常进智能体日志，控制台可以按渠道复盘。

### 3.2 对话链路要先改一处

`OpenChatService.chat` 现在依赖 `HttpServletRequest` 和线程上的 `OpenApiContext`，返回 `ResponseEntity` 或 SSE。IM 消息来自长连接或回调，后台线程处理，没有 HTTP 请求可用。

做法：把 `OpenChatService` 里「校验凭证 → 校验智能体 → 护栏 → 调模型 → 记账」的部分抽成一个不依赖 HTTP 的方法，参数是凭证、请求和一个增量回调（现有代码里已经有 `onDelta` / `cancelled` 的回调接口），返回结构化结果（成功的回复和用量，或拒绝原因码）。原来的 `chat` 改为调用它再包装成 HTTP 响应，开放接口和分享链接的行为不变，现有测试必须全部通过。

### 3.3 模块划分

```
im/
  ImChannel（实体）、ImChannelRepository
  ImChannelService           渠道的增删改查、启停、测试连接、权限、审计
  ImMessageDispatcher        收到消息后的统一处理（第 3.5 节）
  ImConnectionManager        启动 / 停止 / 重连长连接，租约
  ImAdapter（接口）
    FeishuAdapter / DingTalkAdapter / WecomAdapter / QqAdapter
  ImCallbackController       /im/v1/{type}/{channelId}/callback（回调方式的入口）
```

`ImAdapter` 只管和平台打交道：

| 方法 | 作用 |
|---|---|
| `connect(channel, listener)` / `disconnect` | 建立 / 断开长连接（回调方式的平台为空实现） |
| `parseCallback(request)` | 回调方式：校验签名、处理平台的地址验证请求、解析成统一的 `ImInboundMessage` |
| `testCredentials(channel)` | 用凭证换一次 access token，验证配置是否正确 |
| `startReply(message)` | 发出「正在思考…」或创建流式卡片，返回一个回复句柄 |
| `updateReply(handle, textSoFar)` | 流式更新（不支持流式的平台为空实现） |
| `finishReply(handle, finalText)` | 发出最终内容；超长时按平台上限切成多条 |

`ImInboundMessage` 统一字段：平台消息 ID、会话类型（私聊 / 群聊）、群 ID、发送人 ID、发送人昵称（有就带）、是否 @ 了机器人、文字内容、消息类型、平台回复需要的上下文（例如钉钉的 sessionWebhook、QQ 的 msg_id）。

### 3.4 连接管理

- 程序启动时，为所有启用且使用长连接的渠道建立连接；新建、启用、修改凭证后立即重连；停用、删除后断开。
- 断线自动重连，退避 1s、2s、4s… 最长 60s。状态写回渠道表：`CONNECTED / CONNECTING / ERROR / DISABLED`，带最近一次错误和时间，控制台显示。
- **多实例**：同一个渠道同一时间只能有一个实例保持长连接，否则消息会重复处理。用数据库租约：渠道表记 `lease_owner` 和 `lease_until`，持有者每 30 秒续约，超过 90 秒没续约的由其他实例接管。单实例部署（板子）时等于总是自己持有。
- 回调方式不需要租约，哪个实例收到就由哪个处理，靠第 3.5 节的去重避免重复。

### 3.5 收到一条消息的处理流程

1. **去重**：平台超时会重发同一条消息。按「渠道 + 平台消息 ID」去重，记录保留 24 小时（表 `im_message_dedup`，定时清理）。
2. **先确认，后处理**：回调方式立即返回 200，长连接方式立即回 ACK，然后交给后台线程池处理。平台通常要求几秒内确认，等模型回答完再确认会被判定超时并重发。
3. **过滤**：群消息没有 @ 机器人就忽略；机器人自己发的消息忽略；非文字消息回复「暂时只支持文字消息」。
4. **命令**：`/new`（或「新对话」）开始新会话，`/help` 显示用法。
5. **检查渠道**：渠道是否启用、智能体是否运行、用量上限、每人每分钟消息数。不满足时回复通用提示，原因只记在控制台。
6. **找会话**：会话键私聊为 `u:{用户ID}`，群聊为 `g:{群ID}:u:{用户ID}`。超过空闲时间（默认 2 小时，可配置）自动开新会话。
7. **对话**：调用第 3.2 节的对话核心。先 `startReply`，流式平台每 0.5～1 秒 `updateReply` 一次（各平台更新接口有频率限制，需要节流），最后 `finishReply`。
8. **失败**：模型或通道失败时，给 IM 用户只显示「当前无法回答，请稍后再试」（和分享链接一致，不对外暴露内部信息），具体原因写进渠道的「最近错误」和智能体日志。
9. **记账**：消息数、token 记到渠道和发送人上，和分享链接一样。

### 3.6 并发与顺序

- 同一个会话键的消息按顺序处理，上一条没回答完时，新消息排队（最多排 3 条，再多直接回复「请等上一条回答完」）。
- 整体用有界线程池，满了回复「当前使用人数较多，请稍后再试」，不无限堆积。

## 4. 各平台要点

### 4.1 飞书

- 应用类型：企业自建应用，开启机器人能力，订阅「接收消息」事件（`im.message.receive_v1`），申请收发消息权限。
- 接入方式：默认长连接（SDK 自带），不需要公网地址；也支持回调方式（填 Encrypt Key 和 Verification Token）。
- 回复：发一张卡片，内容随生成流式更新；更新失败时退回整段文本消息。
- 群聊：消息里的 mentions 包含机器人自己时才处理，处理前去掉 @ 文本。

### 4.2 钉钉

- 应用类型：企业内部应用，添加机器人，消息接收模式选 Stream 模式。
- 接入方式：Stream 长连接（官方 Java SDK），不需要公网地址。
- 回复：优先用 AI 卡片流式更新；没有卡片权限时，用消息里带的 sessionWebhook 发 Markdown（注意 sessionWebhook 有有效期）。
- 群聊：只有 @ 机器人的消息才会推给机器人。

### 4.3 企业微信

- 机器人类型：企业微信「智能机器人」，在管理后台创建，拿到 Bot ID 和 Secret。
- 接入方式：WebSocket 长连接，协议自带流式回复。开工前先核实官方文档和有没有 Java SDK，没有就按协议用 JDK 自带的 WebSocket 客户端实现。
- 备选：如果智能机器人不满足需求，再考虑「自建应用 + 回调」，但它要求可信 IP 和与企业主体一致的备案域名，配置门槛高，本期不做。

### 4.4 QQ

- 机器人类型：QQ 开放平台机器人，个人或企业主体都能注册，上线前要通过平台审核。
- 接入方式：HTTP 回调，地址形如 `https://{平台域名}/im/v1/qq/{渠道ID}/callback`。按官方文档实现回调地址验证和请求签名校验。
- 回复：只能被动回复。回复时带上收到的消息 ID，同一条消息的多次回复用递增的序号区分；超过平台的被动回复时间窗口就发不出去，所以模型调用要设超时。
- 不能流式：先回一句「正在思考…」（占用一次被动回复），回答完再发完整内容；超长时切成多条。
- 群聊必须 @ 机器人；单聊需要用户先添加机器人。

## 5. 安全

- 平台凭证用 `SecretCrypto` 加密保存，接口和页面只显示末 4 位；修改时不回显原值。
- 回调地址是公开的，必须校验签名，校验失败直接 401 并计数；回调接口加入免登录白名单，只开放 `/im/v1/**`。
- 回调接口按渠道限流，防止被刷。
- 渠道只能由能管理该智能体的人创建和修改（和分享链接同一套权限）；所有修改、启停、测试连接都写审计。
- 智能体关联了知识库时，在配置页提示：IM 里能和机器人对话的人都能通过提问看到知识库内容（和分享链接的提示一致）。
- 谁能用机器人由 IM 平台后台控制（飞书、钉钉的应用可用范围，企业微信的机器人可见范围，QQ 的群和好友）。平台侧本期不做用户白名单，放在后续。
- 每个渠道可以设对话次数和 token 总量上限、每人每分钟消息数（默认 10），用完自动停止回复并提示。

## 6. 数据库（V48）

```sql
CREATE TABLE IF NOT EXISTS agent_im_channels (
    id               VARCHAR(64) PRIMARY KEY,
    agent_id         VARCHAR(64)  NOT NULL,
    platform         VARCHAR(16)  NOT NULL,   -- FEISHU / DINGTALK / WECOM / QQ
    name             VARCHAR(100) NOT NULL,
    connect_mode     VARCHAR(16)  NOT NULL,   -- LONG_CONN / CALLBACK
    credentials      TEXT         NOT NULL,   -- JSON，整体用 SecretCrypto 加密
    status           VARCHAR(16)  NOT NULL,   -- ACTIVE / DISABLED
    conn_state       VARCHAR(16),             -- CONNECTED / CONNECTING / ERROR
    last_error       VARCHAR(500),
    last_error_at    TIMESTAMP,
    last_connected_at TIMESTAMP,
    group_mode       VARCHAR(16)  NOT NULL DEFAULT 'MENTION',  -- 群聊：只在 @ 时回复
    session_idle_minutes INT      NOT NULL DEFAULT 120,
    max_messages     INT,
    max_tokens       BIGINT,
    user_rpm         INT          NOT NULL DEFAULT 10,
    message_count    INT          NOT NULL DEFAULT 0,
    token_count      BIGINT       NOT NULL DEFAULT 0,
    user_count       INT          NOT NULL DEFAULT 0,
    api_key_id       VARCHAR(64),
    lease_owner      VARCHAR(100),
    lease_until      TIMESTAMP,
    last_used_at     TIMESTAMP,
    created_by       VARCHAR(64),
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_im_channels_agent ON agent_im_channels(agent_id);

CREATE TABLE IF NOT EXISTS agent_im_users (          -- IM 用户与会话
    id               VARCHAR(64) PRIMARY KEY,
    channel_id       VARCHAR(64)  NOT NULL,
    session_key      VARCHAR(200) NOT NULL,   -- u:{用户ID} 或 g:{群ID}:u:{用户ID}
    platform_user_id VARCHAR(128) NOT NULL,
    display_name     VARCHAR(100),
    conversation_id  VARCHAR(64),             -- 当前会话
    message_count    INT    NOT NULL DEFAULT 0,
    token_count      BIGINT NOT NULL DEFAULT 0,
    first_seen_at    TIMESTAMP,
    last_seen_at     TIMESTAMP,
    UNIQUE (channel_id, session_key)
);

CREATE TABLE IF NOT EXISTS im_message_dedup (
    channel_id   VARCHAR(64)  NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    received_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (channel_id, message_id)
);
```

编号以开工时为准（目前最高是 V47）。

## 7. 接口

控制台（需要登录，权限同分享链接）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agents/{agentId}/im-channels` | 列表（含连接状态、用量） |
| POST | `/api/agents/{agentId}/im-channels` | 新建 |
| PUT | `/api/agents/{agentId}/im-channels/{id}` | 修改（凭证字段不填表示不改） |
| POST | `.../{id}/test` | 测试凭证 |
| POST | `.../{id}/enable`、`.../{id}/disable` | 启用 / 停用 |
| POST | `.../{id}/reconnect` | 手动重连 |
| DELETE | `.../{id}` | 删除（同时吊销内部凭证；对话记录保留） |
| GET | `.../{id}/users`、`.../{id}/conversations`、`.../{id}/conversations/{cid}` | 用户与对话记录 |

平台回调（公开，校验签名）：`POST /im/v1/{platform}/{channelId}/callback`。

AI 助手：增加 `list_im_channels`（查询）和 `disable_im_channel`（B 级卡片），同步更新 `capabilities.json`、评测用例和平台功能说明。

## 8. 界面

- 「访问入口」增加第三个页签「IM 机器人」，和「Web 应用」「后端服务 API」并列。
- 列表沿用分享链接的表格布局：名称、平台图标、连接状态（已连接 / 连接中 / 出错，出错时悬停显示原因）、用量、最近使用、操作（测试、重连、对话记录、编辑、停用、删除）。
- 新建时先选平台，再按平台显示分步说明：去哪个后台建应用、要开哪些权限、复制哪几个值、回调方式时显示要填到平台的回调地址（带复制按钮）。
- 保存后自动测试凭证，成功了再建立连接；失败时显示平台返回的错误。
- 用户与对话记录弹窗复用分享链接的访客记录组件。

## 9. 开发顺序与工期

| 步骤 | 内容 | 粗估 |
|---|---|---|
| ① 框架 | 第 3.2 节对话核心抽取（含回归）、V48、渠道管理接口、连接管理和租约、消息处理流程（去重、排队、命令、限流、记账）、「IM 机器人」页签和通用配置页 | 4～5 天 |
| ② 飞书 | 长连接、流式卡片、私聊和群 @、回调方式、配置向导 | 3 天 |
| ③ 钉钉 | Stream 模式、AI 卡片、sessionWebhook 兜底、配置向导 | 3 天 |
| ④ 企业微信 | 智能机器人长连接和流式回复（先核实协议）、配置向导 | 3～4 天 |
| ⑤ QQ | 回调地址验证与签名、被动回复、切分长消息、配置向导；另外等平台审核 | 3 天 |
| ⑥ 收尾 | AI 助手工具、平台功能说明、部署文档（各平台的申请步骤）、版本号 | 1～2 天 |

合计约 17～20 个工作日（一个人）。每一步做完都可以单独发版，① 和 ② 一起交付第一版。

依赖：飞书、钉钉的 Java SDK 要联网下载 Maven 依赖；每个平台都需要一个测试用的企业 / 机器人账号，开工前准备好。

## 10. 验收

每个平台都要测：

1. 私聊多轮对话，上下文连续；`/new` 后开始新会话；空闲超时后自动开新会话。
2. 群里不 @ 不回复，@ 后回复，不同人的会话互不影响。
3. 流式平台能看到内容逐步出现；QQ 先收到「正在思考…」再收到完整回复；超长回复正确切分。
4. 同一条消息平台重发时只回复一次。
5. 停用渠道、停用智能体、用完上限后，用户收到通用提示，控制台能看到原因。
6. 模型通道故障时，用户收到通用提示，控制台「最近错误」和智能体日志里有具体原因。
7. 断网后自动重连，控制台状态从「出错」恢复到「已连接」。
8. 两个实例同时运行时，同一渠道只有一个实例持有连接，消息不重复回复；停掉持有者后另一实例在 90 秒内接管。
9. 回调接口：签名错误返回 401；伪造的请求不会触发对话。
10. 凭证在数据库里是密文，接口只返回末 4 位；所有修改都有审计记录。
11. 开放接口和分享链接的现有测试全部通过（第 3.2 节的改动不影响它们）。

## 11. 风险与待核实

- **企业微信智能机器人的协议**：长连接和流式回复的细节、有没有官方 Java SDK，开工 ④ 前核实。
- **QQ 审核与权限**：Markdown 消息、群聊等能力可能需要单独申请；被动回复时间窗口内模型必须答完，慢模型可能来不及，需要给 QQ 渠道单独设超时并提示用户。
- **流式更新频率限制**：各平台卡片更新接口都有限频，节流间隔按实测调整。
- **平台政策变化**：QQ 已经收紧过一次主动消息，后续可能继续调整；适配层要能单独升级。
- **外部用户经由企业 IM**：飞书、钉钉、企业微信的自建应用主要面向本组织成员，外部用户能否使用取决于各平台的外部联系人 / 外部群策略，按实际需要在平台后台配置。

## 12. 后续（不在本期）

- 平台侧用户白名单、把 IM 用户绑定到平台账号（按平台账号权限回答）。
- 收图片、文件（随 3.9 图文能力）、语音转文字。
- 卡片按钮（例如「重新回答」「点赞 / 点踩」反馈）。
- 企业微信「微信客服」，服务外部微信用户。
- 主动推送（飞书、钉钉、企业微信支持的范围内）。
