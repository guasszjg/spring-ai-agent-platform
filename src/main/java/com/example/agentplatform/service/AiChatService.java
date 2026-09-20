package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.ChatGeneration;
import com.example.agentplatform.model.ChatMessage;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AgentService agentService;
    private final AgentConversationService conversationService;
    private final LlmGatewayService gatewayService;
    private final OpenAiCompatibleClient openAiClient;
    private final CustomHttpLlmClient customHttpClient;
    private final ChatClient chatClient;
    private final com.example.agentplatform.tool.AgentToolRegistry toolRegistry;
    private final AgentToolSecretService toolSecretService;
    private final PlatformToolService platformToolService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ResourceAuthorizationService resourceAuthorizationService;
    private final boolean simulationFallbackEnabled;

    public AiChatService(AgentService agentService,
                         AgentConversationService conversationService,
                         LlmGatewayService gatewayService,
                         OpenAiCompatibleClient openAiClient,
                         CustomHttpLlmClient customHttpClient,
                         com.example.agentplatform.tool.AgentToolRegistry toolRegistry,
                         AgentToolSecretService toolSecretService,
                         PlatformToolService platformToolService,
                         KnowledgeBaseService knowledgeBaseService,
                         ResourceAuthorizationService resourceAuthorizationService,
                         @Autowired(required = false) ChatModel chatModel,
                         @Value("${app.ai.simulation-fallback:false}") boolean simulationFallbackEnabled) {
        this.agentService = agentService;
        this.conversationService = conversationService;
        this.gatewayService = gatewayService;
        this.openAiClient = openAiClient;
        this.customHttpClient = customHttpClient;
        this.toolRegistry = toolRegistry;
        this.toolSecretService = toolSecretService;
        this.platformToolService = platformToolService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.resourceAuthorizationService = resourceAuthorizationService;
        this.simulationFallbackEnabled = simulationFallbackEnabled;
        this.chatClient = (chatModel != null) ? ChatClient.builder(chatModel).build() : null;
    }

    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        Agent agent = agentService.getById(request.getAgentId())
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + request.getAgentId()));

        com.example.agentplatform.security.CurrentActor actor = com.example.agentplatform.security.CurrentActor.get();
        if (actor != null && !resourceAuthorizationService.canRunAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：无权运行或调用该智能体");
        }

        // 强校验智能体绑定的知识库依赖权限：
        // 智能体拥有者对所绑定的每一个知识库必须拥有有效的 USE 权限；
        // 若依赖失效或授权已撤销，在此立即阻断，严禁调用 LLM 或 Dify，且不产生任何数据库会话写入与外部调用！
        resourceAuthorizationService.checkAgentKnowledgeBaseDependencies(agent);

        String userMessage = request.getMessage();
        String reply;
        String executionModel = agent.getModelName() != null && !agent.getModelName().isBlank()
                ? agent.getModelName()
                : "未指定";
        int promptTokens = 0;
        int completionTokens = 0;
        boolean realModelReply = false;
        String[] routedModel = { executionModel };
        String[] toolCalledHolder = { null };
        if (platformToolService != null) {
            toolRegistry.setPlatformConfigs(platformToolService.runtimeConfigs());
            toolRegistry.setHttpTools(platformToolService.httpRuntimeSpecs());
        }
        List<java.util.Map<String, Object>> tools = toolRegistry.getToolDefinitions(request.getEnabledTools());

        // 当前请求 Key 优先，否则使用该智能体在数据库中加密保存的 Key。
        String bochaKey = firstNonBlank(
                extractBochaApiKey(request.getToolConfigs()),
                toolSecretService.getBochaApiKey(agent.getId())
        );
        if (bochaKey != null && !bochaKey.isBlank()) {
            toolRegistry.setCurrentContextBochaApiKey(bochaKey);
        }

        try {
            OpenAiCompatibleClient.ChatResult routed = invokeViaGateway(agent, userMessage, request.getHistory(),
                    request.getGeneration(), request.getPrompt(), tools, request.getEnabledTools(), routedModel);
            if (routed != null && routed.content() != null && !routed.content().isBlank()) {
                reply = routed.content();
                executionModel = routedModel[0];
                promptTokens = Math.max(0, routed.promptTokens());
                completionTokens = Math.max(0, routed.completionTokens());
                if (promptTokens + completionTokens == 0 && routed.totalTokens() > 0) {
                    completionTokens = routed.totalTokens();
                }
                realModelReply = true;
                if (routed.toolCalled() != null) {
                    toolCalledHolder[0] = routed.toolCalled();
                }
            } else if (chatClient != null) {
                log.info("Invoking Spring AI ChatClient for Agent: [{}] with model: [{}]", agent.getName(), executionModel);
                
                String instruction = buildEffectiveInstruction(request.getPrompt(), agent, userMessage, request.getEnabledTools());
                var clientRequest = chatClient.prompt()
                        .system(instruction);

                // 添加历史记录
                if (request.getHistory() != null && !request.getHistory().isEmpty()) {
                    for (ChatMessage msg : request.getHistory()) {
                        if ("user".equalsIgnoreCase(msg.getRole())) {
                            clientRequest = clientRequest.user(msg.getContent());
                        }
                    }
                }

                clientRequest = clientRequest.user(userMessage);
                var aiResponse = clientRequest.call().chatResponse();

                if (aiResponse != null && aiResponse.getResult() != null && aiResponse.getResult().getOutput() != null) {
                    reply = aiResponse.getResult().getOutput().getText();
                    realModelReply = true;
                    if (aiResponse.getMetadata() != null && aiResponse.getMetadata().getUsage() != null) {
                        var usage = aiResponse.getMetadata().getUsage();
                        promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                        completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    }
                } else {
                    reply = fallbackReply(agent, userMessage, request.getHistory(), toolCalledHolder, null);
                }
            } else {
                reply = fallbackReply(agent, userMessage, request.getHistory(), toolCalledHolder, null);
            }
        } catch (Exception ex) {
            log.warn("模型调用失败: {}", ex.getMessage());
            reply = fallbackReply(agent, userMessage, request.getHistory(), toolCalledHolder, ex);
        } finally {
            toolRegistry.clearCurrentContext();
        }

        reply = cleanAnswer(reply);

        long latencyMs = System.currentTimeMillis() - startTime;
        int tokens = promptTokens + completionTokens;
        agentService.recordInvocation(agent.getId(), latencyMs, promptTokens, completionTokens, realModelReply);
        if (realModelReply) {
            agentService.recordExecutedModel(agent.getId(), executionModel);
        }

        ChatResponse response = new ChatResponse(
                agent.getId(),
                agent.getName(),
                reply,
                latencyMs,
                executionModel,
                tokens
        );
        response.setToolCalled(toolCalledHolder[0]);
        response.setDegraded(!realModelReply);
        response.setSource(realModelReply ? "MODEL" : "SIMULATION");
        AgentConversation conversation = conversationService.appendTurn(
                agent.getId(),
                request.getConversationId(),
                request.getAccount(),
                userMessage,
                reply,
                executionModel,
                latencyMs,
                tokens
        );
        response.setConversationId(conversation.getId());
        return response;
    }

    private OpenAiCompatibleClient.ChatResult invokeViaGateway(Agent agent, String userMessage,
                                                               List<ChatMessage> history, ChatGeneration generation,
                                                               String livePrompt, List<java.util.Map<String, Object>> tools,
                                                               List<String> enabledTools,
                                                               String[] routedModel) {
        ChatGeneration effective = generation != null ? generation : fromAgent(agent);
        final String instruction = buildEffectiveInstruction(livePrompt, agent, userMessage, enabledTools);
        return gatewayService.resolveRoute(agent.getModelName()).map(route -> {
            var messages = OpenAiCompatibleClient.toMessages(
                    instruction,
                    userMessage,
                    history
            );
            OpenAiCompatibleClient.ChatResult result = callProvider(route.primary(), route.primaryKey(),
                    agent.getModelName(), effective, route.timeoutMs(), route.maxRetries(), messages, tools, routedModel);
            if (result != null) {
                return result;
            }
            if (route.fallback() != null && gatewayService.hasKey(route.fallback())) {
                log.warn("Primary LLM channel [{}] failed, switching to fallback [{}]",
                        route.primary().getName(), route.fallback().getName());
                return callProvider(route.fallback(), route.fallbackKey(),
                        agent.getModelName(), effective, route.timeoutMs(), route.maxRetries(), messages, tools, routedModel);
            }
            return null;
        }).orElse(null);
    }

    public String buildEffectiveInstruction(String rawPrompt, Agent agent, String userMessage) {
        return buildEffectiveInstruction(rawPrompt, agent, userMessage, null);
    }

    public String buildEffectiveInstruction(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools) {
        String base = firstNonBlank(rawPrompt, agent.getSystemPrompt(), "你是一个通用智能助手。");

        // 1. 获取当前系统真实时间与星期 (标准北京时间 Asia/Shanghai)
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);

        // 2. 替换提示词中可能包含的变量占位符 (支持 Dify 及通用模版变量规范)
        String processed = base
                .replace("{{system_time}}", timeStr + " (" + weekday + ")")
                .replace("{{sys.time}}", timeStr)
                .replace("{{time}}", timeStr)
                .replace("{{sys.date}}", dateStr)
                .replace("{{date}}", dateStr)
                .replace("{{today}}", dateStr + " " + weekday)
                .replace("{{input}}", userMessage != null ? userMessage : "");

        // 3. 构建高优先级系统基准时间环境约束（Grounding Time Context）
        StringBuilder timeContext = new StringBuilder(String.format(
                "【系统基准环境】\n" +
                "- 当前日期：%s（%s）\n" +
                "- 当前时间：%s\n" +
                "- 所在时区：Asia/Shanghai (GMT+8 / 北京时间)\n" +
                "【时效性准则】以上为您所在物理世界的绝对真实系统时间。在处理涉及“今天”、“现在”、“当前”、“今年”、“本月”或日期相对计算时，必须严格以此时间为准。若调用了联网检索工具（如博查搜索），检索到的互联网文章可能包含历史发布的旧快照，切勿将历史网页的发布时间误认为当前的真实日期！",
                dateStr, weekday, timeStr
        ));

        // 若挂载了扩展工具，注入工具调用路由规范，消除模型对时间工具与联网搜索的二义性竞争
        if (enabledTools != null && !enabledTools.isEmpty()) {
            boolean hasTime = enabledTools.stream().anyMatch(t -> t.contains("时间") || t.contains("time") || t.contains("时区") || t.contains("星期"));
            boolean hasBocha = enabledTools.stream().anyMatch(t -> t.contains("联网") || t.contains("bocha") || t.contains("检索"));

            StringBuilder toolGuide = new StringBuilder("\n\n【工具调用指引规范】\n");
            if (hasTime) {
                toolGuide.append("- 当前智能体已装配时间工具集（包含获取当前时间、星期几计算器等）。当用户询问“今天多少号”、“今天几号”、“现在几点”、“当前日期”等本地时序问题时，可直接参考系统基准时间或优先调用 `time_get_current_time` 工具，严禁使用联网搜索查询当前本地系统时间！\n");
            }
            if (hasBocha) {
                toolGuide.append("- 联网检索工具（`bocha_web_search`）仅用于查询外部最新新闻、实时天气或全网事实，切勿使用联网检索来查问当前的本地系统时间！\n");
            }
            timeContext.append(toolGuide.toString().trim());
        }

        // 注入到系统指令头部，赋予模型确定性的时序与工具调度认知
        String combined = timeContext + "\n\n" + processed;

        // 4. 追加知识库检索片段（RAG 上下文）
        return appendKnowledgeContext(combined, agent, userMessage);
    }

    private String appendKnowledgeContext(String instruction, Agent agent, String userMessage) {
        String rag = knowledgeBaseService.buildRetrievalContext(agent.getKnowledgeBaseIds(), userMessage);
        if (rag == null || rag.isBlank()) {
            return instruction;
        }
        if (instruction == null || instruction.isBlank()) {
            return rag;
        }
        return instruction + "\n\n" + rag;
    }

    private ChatGeneration fromAgent(Agent agent) {
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(agent.getTemperature());
        generation.setTopP(agent.getTopP());
        generation.setMaxTokens(agent.getMaxTokens());
        return generation;
    }

    private OpenAiCompatibleClient.ChatResult callProvider(com.example.agentplatform.model.LlmProvider provider,
                                                           String apiKey, String requestedModel, ChatGeneration generation,
                                                           int timeoutMs, int maxRetries,
                                                           List<java.util.Map<String, Object>> messages,
                                                           List<java.util.Map<String, Object>> tools,
                                                           String[] routedModel) {
        String model = pickModel(provider, requestedModel);
        routedModel[0] = model;
        int attempts = Math.max(1, maxRetries + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                if (provider.getProtocol() == com.example.agentplatform.model.LlmProtocolType.CUSTOM_HTTP) {
                    log.info("Gateway routing agent chat via custom HTTP endpoint [{}]", provider.getName());
                    return customHttpClient.chat(provider, messages, generation, timeoutMs);
                }
                log.info("Gateway routing agent chat via [{}] model [{}] with {} tools", provider.getName(), model, tools != null ? tools.size() : 0);
                var customHeaders = LlmGatewayService.parseCustomHeaders(provider.getCustomConfig());
                boolean defaultWebSearch = LlmGatewayService.isWebSearchEnabled(provider);
                return openAiClient.chatWithTools(provider.getBaseUrl(), apiKey, model, customHeaders, defaultWebSearch, messages, tools, toolRegistry, generation, timeoutMs);
            } catch (Exception ex) {
                log.warn("Gateway channel [{}] attempt {} failed: {}", provider.getName(), i + 1, ex.getMessage());
            }
        }
        return null;
    }

    private String pickModel(com.example.agentplatform.model.LlmProvider provider, String requested) {
        if (requested != null && !requested.isBlank()
                && provider.getModels() != null
                && java.util.Arrays.stream(provider.getModels().split("[,，]"))
                .map(String::trim)
                .anyMatch(item -> item.equalsIgnoreCase(requested.trim()))) {
            return requested.trim();
        }
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            return provider.getDefaultModel();
        }
        return requested;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static String cleanAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        return answer
                .replaceAll("\\[\\d+\\]", "")
                .replaceAll("【\\d+】", "")
                .replaceAll("[¹²³⁴⁵⁶⁷⁸⁹⁰]+", "")
                .trim();
    }

    private String generateSmartSimulationReply(Agent agent, String userMessage, List<ChatMessage> history, String[] toolCalledHolder) {
        String name = agent.getName();
        String category = agent.getCategory() != null ? agent.getCategory() : "通用智能";

        // 优先检查是否有智能时间/工具意图并调用对应真实工具
        if (userMessage.contains("星期") || userMessage.contains("周几")) {
            toolCalledHolder[0] = "time.calculate_weekday (星期几计算器)";
            String dateParam = java.time.LocalDate.now().toString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}").matcher(userMessage);
            if (m.find()) {
                dateParam = m.group().replace('年', '-').replace('月', '-').replace("日", "").trim();
            }
            String toolResult = toolRegistry.execute("time_calculate_weekday", "{\"date\":\"" + dateParam + "\"}");
            return "### 🤖 [" + name + "] 智能工具调度结果\n\n" +
                    "已识别时间计算意图，调用工具 `time.calculate_weekday` 完成运算：\n\n" +
                    "> " + toolResult + "\n\n" +
                    "*(注：当前运行在智能模拟回退模式。在网关配置真实模型后，将由 LLM 自主执行 Function Calling 解析复杂日期语义)*";
        } else if (userMessage.contains("几号") || userMessage.contains("多少号") || userMessage.contains("日期")
                || userMessage.contains("今天") || userMessage.contains("时间") || userMessage.contains("几点") || userMessage.contains("时区")) {
            String tz = "Asia/Shanghai";
            if (userMessage.contains("纽约")) tz = "America/New_York";
            else if (userMessage.contains("伦敦")) tz = "Europe/London";
            else if (userMessage.contains("东京")) tz = "Asia/Tokyo";
            else if (userMessage.contains("巴黎")) tz = "Europe/Paris";

            toolCalledHolder[0] = "time.get_current_time (timezone=" + tz + ")";
            String toolResult = toolRegistry.execute("time_get_current_time", "{\"timezone\":\"" + tz + "\"}");
            return "### 🤖 [" + name + "] 智能工具调度结果\n\n" +
                    "已识别时间查询意图，调用工具 `time.get_current_time` 获取当前即时时间：\n\n" +
                    "> " + toolResult + "\n\n" +
                    "*(注：当前运行在智能模拟回退模式。在网关配置真实模型后，将由 LLM 自主执行 Function Calling 自动识别全球时区)*";
        } else if (userMessage.contains("搜索") || userMessage.contains("联网") || userMessage.contains("最新")
                || userMessage.contains("天气") || userMessage.contains("气温") || userMessage.contains("下雨") || userMessage.contains("雨")) {
            toolCalledHolder[0] = "bocha.web_search (联网检索)";
            String toolResult = toolRegistry.execute("bocha_web_search", "{\"query\":\"" + userMessage + "\"}");
            return "### 🌐 [" + name + "] 联网检索响应\n\n" +
                    toolResult + "\n\n" +
                    "*(注：已调用 Bocha 联网检索实时信息并整合最新权威来源)*";
        }

        // 基于智能体角色产生高质量模拟响应
        if ("Spring Boot 架构专家".equals(name) || "代码研发".equals(category)) {
            return "### 🤖 [" + name + "] 架构分析建议\n\n" +
                    "针对你提出的问题 **「" + userMessage + "」**，在 **Spring Boot 4.x + Spring AI 2.0.1** 体系下，推荐以下最佳实践方案：\n\n" +
                    "```java\n" +
                    "@Configuration\n" +
                    "public class AgentAiConfig {\n\n" +
                    "    @Bean\n" +
                    "    public ChatClient customChatClient(ChatModel chatModel) {\n" +
                    "        return ChatClient.builder(chatModel)\n" +
                    "                .defaultSystem(\"" + (agent.getSystemPrompt() != null ? agent.getSystemPrompt().replace("\"", "\\\"") : "你是一个智能助理") + "\")\n" +
                    "                .build();\n" +
                    "    }\n" +
                    "}\n" +
                    "```\n\n" +
                    "#### 💡 核心设计与考量：\n" +
                    "1. **解耦与编排**：利用 Spring AI 2.0.1 的 `ChatClient` 链式调用 API，支持动态注入 Prompt 与 Function Calling。\n" +
                    "2. **高并发与容错**：在微服务环境下建议配置 Resilience4j 断路器与限流策略，防止大模型调用超时导致线程池耗尽。\n" +
                    "3. **可观测性**：Spring Boot 4.x 原生集成了 Micrometer Tracing，可自动记录 AI 调用的 Token 消耗与 P99 延迟。";
        } else if ("SQL & DB 调优大师".equals(name) || "数据分析".equals(category)) {
            return "### ⚡ [" + name + "] 数据库深度调优报告\n\n" +
                    "针对 **「" + userMessage + "」** 的数据查询与架构场景分析：\n\n" +
                    "#### 1. 执行计划诊断 (EXPLAIN Analyze)\n" +
                    "- **潜在瓶颈**：全表扫描 (ALL) 或临时表文件排序 (Using filesort)。\n" +
                    "- **建议复合索引**：\n" +
                    "```sql\n" +
                    "-- 推荐创建联合覆盖索引，遵循最左前缀匹配原则\n" +
                    "CREATE INDEX idx_agent_status_created ON t_agent_logs (status, created_at DESC, user_id);\n" +
                    "```\n\n" +
                    "#### 2. 分页深分页优化策略\n" +
                    "当偏移量 `OFFSET` 过大时，改用延迟关联（Deferred Join）或子查询游标定位：\n" +
                    "```sql\n" +
                    "SELECT a.* FROM t_agent_logs a\n" +
                    "JOIN (SELECT id FROM t_agent_logs WHERE status = 1 ORDER BY id DESC LIMIT 10000, 20) t ON a.id = t.id;\n" +
                    "```";
        } else if ("企业知识库客服助理".equals(name) || "知识库客服".equals(category)) {
            return "您好！我是 **" + name + "**。关于您咨询的问题：\n\n" +
                    "「" + userMessage + "」\n\n" +
                    "为您检索到企业最新知识库解答如下：\n" +
                    "1. 我们的智能体管理平台已全面支持 **Spring AI 2.0.1**，提供统一的模型调度与 Prompt 模版管理。\n" +
                    "2. 系统支持多租户隔离、动态参数微调（Temperature, Top-P）以及 7x24 小时流式响应。\n" +
                    "3. 如果您需要接入企业专属私有知识库 (Vector DB / Milvus / PgVector)，可在智能体配置中开启 RAG 扩展插件。\n\n" +
                    "如需进一步人工协助，欢迎随时点击右上角人工客服通道！";
        } else if ("Kubernetes 运维守护者".equals(name) || "运维架构".equals(category)) {
            return "### 🛡️ [" + name + "] 集群诊断与排查指令\n\n" +
                    "收到故障排查请求：**「" + userMessage + "」**\n\n" +
                    "#### 推荐快速排查步骤：\n" +
                    "```bash\n" +
                    "# 1. 检查 Pod 状态与重启原因\n" +
                    "kubectl get pods -n prod -l app=agent-platform -o wide\n\n" +
                    "# 2. 查看最近的崩溃日志与 OOM 退出码\n" +
                    "kubectl logs --previous -n prod deployment/agent-platform-deployment\n\n" +
                    "# 3. 实时跟踪 CPU / 内存压力\n" +
                    "kubectl top pods -n prod --sort-by=memory\n" +
                    "```\n\n" +
                    "**建议**：若发生 `OOMKilled (Exit Code 137)`，请调大 JVM `-XX:MaxRAMPercentage=75.0` 并调整 K8s `resources.limits.memory`。";
        } else {
            return "你好！我是 **" + name + "**（" + category + " 智能体）。\n\n" +
                    "我已收到你的指令：**「" + userMessage + "」**。\n\n" +
                    "根据当前配置的系统设定（Prompt: *" + (agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "专业助手") + "*）：\n" +
                    "- 我已结合设定角色完成了思考与拆解。\n" +
                    "- 当前调度引擎：`" + (agent.getModelName() != null ? agent.getModelName() : "GPT-4o") + "`，采样温度：`" + agent.getTemperature() + "`。\n\n" +
                    "如果你有更详细的上下文或特定需求，请直接告诉我，我将为你继续深度处理！";
        }
    }

    private String fallbackReply(Agent agent, String userMessage, List<ChatMessage> history,
                                 String[] toolCalledHolder, Exception cause) {
        if (!simulationFallbackEnabled) {
            throw new IllegalStateException("当前没有可用的模型通道，请检查模型网关配置", cause);
        }
        log.warn("Simulation fallback is enabled; returning a clearly marked non-model response");
        return generateSmartSimulationReply(agent, userMessage, history, toolCalledHolder);
    }

    private String extractBochaApiKey(java.util.Map<String, Object> toolConfigs) {
        if (toolConfigs != null) {
            Object bochaObj = toolConfigs.get("bochaApiKey");
            if (bochaObj != null && !bochaObj.toString().isBlank()) {
                return bochaObj.toString().trim();
            }
            Object apiObj = toolConfigs.get("apiKey");
            if (apiObj != null && !apiObj.toString().isBlank()) {
                return apiObj.toString().trim();
            }
        }
        return null;
    }
}
