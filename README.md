# 🤖 Spring AI 智能体管理平台 (Agent Management Platform)

基于 **Spring Boot 4.0.0** 与 **Spring AI 2.0.1** 构建的现代化企业级智能体统一管理与编排平台。

---

## 🌟 核心特性

1. **最新技术栈底座**
   - **Spring Boot 4.x (4.0.0)**
   - **Spring AI 2.0.1 (最新正式稳定版)**
   - **Java 21 LTS**
   - 原生支持 Spring AI `ChatClient` 链式调用、Prompt 模板编排与 Function Calling 工具生态。

2. **现代化极客风格登录页**
   - 采用深色/明亮双主题自适应玻璃拟态 (Glassmorphism)。
   - 融入科技感动态光晕与 Spring AI 专属身份标识。
   - 提供「超级管理员」与「开发者」一键快捷体验账号填充。

3. **双布局智能体控制台 (Dashboard)**
   - 🔲 **卡片布局 (Card View)**：科技感玻璃卡片，直观展示头像、模型、温度、Prompt 概要及调用指标。
   - ☰ **列表布局 (Table View)**：紧凑型数据表格，支持多字段对齐与高效批量管理。
   - **全维度筛选与检索**：支持关键词 (名称/Prompt/标识/标签)、业务分类、运行状态实时过滤。
   - **动态分页机制**：支持动态分页展示、页码切换与记录数统计。
   - **全生命周期管理**：新建智能体、预设专家模版一键套用、在线编辑、启停切换与安全删除。

4. **Dify 风格双栏智能体编排与调试工作台 (`/debug.html`)**
   - **左侧编排区 (Orchestration)**：
     - 📝 **提示词 (Prompt)**：支持字符实时统计与 **✨ AI 自动生成/优化提示词**。
     - 🧩 **变量 (Variables)**：支持用户输入变量（如 `{{input}}`、`{{system_time}}`）动态插值。
     - 📚 **知识库 (RAG)**：支持检索增强知识库挂载与元数据过滤。
     - 🛠️ **工具 (Function Calling)**：集成时区转换、时间戳转换、联网检索等多项工具组件开关。
   - **右侧调试与预览区 (Preview Sandbox)**：
     - 💬 实时会话流、Markdown 格式化与代码高亮。
     - ⚡ **Function Calling 工具调用气泡**：可视化展示工具触发与执行结果。
     - ⏱️ 实时遥测指标监控（响应延迟、Token 消耗、传输速率）。
     - 🚀 支持快捷测试 Prompt 注入与一键重置会话。

5. **全站 Dark / Light 亮暗主题切换**
   - 导航栏一键无缝切换 🌙 暗黑模式 / ☀️ 明亮模式。
   - 主题状态持久化存储于 `localStorage`，全页面统一生效。

---

## 📂 项目工程结构

```text
spring_ai/
  ├── pom.xml                                      # Maven 依赖（Spring Boot 4.0.0 + Spring AI 2.0.1 BOM）
  ├── README.md                                    # 项目详细说明文档
  └── src/main/
      ├── java/com/example/agentplatform/
      │   ├── AgentPlatformApplication.java        # Spring Boot 启动入口类
      │   ├── config/
      │   │   └── WebMvcConfig.java                # WebMVC 路由与静态资源配置
      │   ├── controller/
      │   │   ├── AuthController.java              # 登录与会话接口
      │   │   ├── AgentController.java             # 智能体 CRUD、分页、状态管理接口
      │   │   ├── ChatController.java              # 对话调试接口
      │   │   └── DashboardController.java         # 仪表盘统计接口
      │   ├── model/                               # 数据模型与 DTO
      │   ├── repository/
      │   │   └── AgentRepository.java             # 内存数据存储与预设智能体资产
      │   └── service/
      │       ├── AgentService.java                # 智能体业务逻辑
      │       └── AiChatService.java               # Spring AI ChatClient 调度与推理
      └── resources/
          ├── application.yml                      # 配置文件 (端口, Spring AI 参数)
          └── static/                              # 现代化前端界面
              ├── css/
              │   ├── common.css                   # 全局样式与 Dark/Light 主题变量
              │   ├── login.css                    # 登录页面样式
              │   ├── dashboard.css                # 控制台卡片与列表样式
              │   └── debug.css                    # Dify 风格左右双栏编排调试样式
              ├── js/
              │   ├── api.js                       # 统一 REST API 与主题管理
              │   ├── login.js                     # 登录交互脚本
              │   ├── dashboard.js                 # 控制台卡片/列表渲染与分页
              │   └── debug.js                     # 编排与沙箱调试交互脚本
              ├── login.html                       # 登录页面
              ├── index.html                       # 智能体管理控制台 (Dashboard)
              └── debug.html                       # 智能体编排与调试工作台 (Dify 风格)
```

---

## 🚀 快速启动指南

### 1. 运行服务
在项目根目录 `H:\desk\guass_work\spring_ai` 下执行：
```bash
mvn spring-boot:run
```
或者执行编译打包生成的 Jar：
```bash
java -jar target/spring-ai-agent-platform-1.0.0.jar
```

### 2. 访问控制台
* 🌐 **访问地址**：[http://localhost:8080](http://localhost:8080)
* 🔑 **预设体验账号**：
  * **超级管理员**：`admin` / `admin123`
  * **开发者账号**：`developer` / `dev123456`
  *(支持页面一键点击快速填充)*
