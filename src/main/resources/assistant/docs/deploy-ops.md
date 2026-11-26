# 部署与运维（仅超级管理员）

给负责打包、部署、升级平台的超级管理员与运维人员看。

## 1. 环境要求

- JDK 21、Maven 3.6+、Node.js（前端 `spring_ai_ui` 构建）
- PostgreSQL 17，需安装 `pgvector` 与 `pg_trgm` 扩展
- 后端框架：Spring Boot 4.0 + Spring AI 2.0.3

## 2. 构建与打包

前端（`spring_ai_ui`）构建产物输出到后端 `src/main/resources/static/`，随 Jar 一起发布。前端有改动时先构建前端再打包后端：
1. 在 `spring_ai_ui` 执行 `npm run build`
2. 在 `spring_ai` 执行 `mvn clean package`（运行全部单元测试并产出 `target/spring-ai-agent-platform-<版本>.jar`）

## 3. 配置与启动

- `dev`（默认）：`ddl-auto=update`，自动读取不入库的 `application-local.properties`（直接跑 Jar 时用 `--spring.config.additional-location=src\main\resources\application-local.properties`），允许内网模型地址，开启模拟兜底。
- `prod`：`ddl-auto=validate`，所有敏感配置从环境变量读取（`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`APP_CRYPTO_SECRET`、`OPENAI_API_KEY`、`OPENAI_BASE_URL`），禁止内网地址与模拟兜底，Cookie 仅 HTTPS。
- **重要**：`APP_CRYPTO_SECRET` 是数据库密钥加密主密钥，**设定后不可更改**；更改会导致库内所有已加密的模型 Key、向量/OCR/Dify 凭证无法解密，只能逐个重填。
- 默认端口 `8080`，控制台地址 `http://<host>:8080`。

常用配置项（`application.properties`）：
- `spring.servlet.multipart.max-file-size` / `max-request-size`：HTTP 层上传上限（默认 `520MB` / `600MB`，用于放行最大 500MB 的 FAQ 本地视频；业务层仍限制知识库文档 15MB、FAQ 图片 10MB）。若前端经 Nginx 反向代理，请同步配置 `client_max_body_size 520M;`。
- `server.servlet.session.timeout`：控制台会话超时（默认 30m）
- `app.assistant.rate-limit-per-minute` / `max-concurrent` / `retention-days`：AI 助手限流（20次/分）、最大并发（16）、历史保留天数（90天）
- `app.storage.local.root-dir`：知识库上传文件本地归档目录（默认 `./data/kb-files`）
- `app.rag.keyword.bm25-enabled` / `bm25-fusion-min-score`：BM25 内存关键词检索开关与融合最低分比例（默认 0.6）

## 4. 数据库迁移（Flyway）

启动时自动执行 `src/main/resources/db/migration/` 下的迁移脚本。必须遵守三条规则：
1. 任何环境已执行过的迁移脚本不能再修改（Flyway 会校验校验和，修改会导致启动失败）；调整结构需新增版本。
2. 每个迁移脚本必须可重复执行（使用 `IF NOT EXISTS`、`ON CONFLICT`、条件 `DO` 块），因为 `baseline-version=0` 会在旧库上重放 `V1..Vn`。
3. 修改实体字段必须同步新增 Flyway 迁移，否则 `prod`（`ddl-auto=validate`）会启动失败。

## 5. 首次部署检查清单

1. 用超级管理员登录并立即修改默认密码；
2. 「模型网关 → 大语言模型」配置并测通至少一个通道，设为默认通道（按需配降级备用与故障转移）；
3. 「模型网关 → 向量模型」配置并激活一个向量模型（自建知识库与平台使用文档同步必需）；
4. 按需配置 OCR 识别、Rerank 模型与 Dify 知识引擎。
