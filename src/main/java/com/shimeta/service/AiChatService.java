package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.ChatGeneration;
import com.shimeta.model.ChatMessage;
import com.shimeta.model.ChatRequest;
import com.shimeta.model.ChatResponse;
import com.shimeta.trace.ChatTrace;
import com.shimeta.trace.ChatTraceContext;
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
    private final com.shimeta.tool.AgentToolRegistry toolRegistry;
    private final AgentToolSecretService toolSecretService;
    private final PlatformToolService platformToolService;
    /** 平台设置（单次回答最多调用工具轮数）；setter 注入，单元测试里可以不提供 */
    private PlatformSettingService platformSettingService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformSettingService(PlatformSettingService platformSettingService) {
        this.platformSettingService = platformSettingService;
    }

    private int maxToolRounds() {
        return platformSettingService != null ? platformSettingService.maxToolRounds() : OpenAiCompatibleClient.DEFAULT_MAX_TOOL_ROUNDS;
    }
    private final KnowledgeBaseService knowledgeBaseService;
    private final ResourceAuthorizationService resourceAuthorizationService;

    public AiChatService(AgentService agentService,
                         AgentConversationService conversationService,
                         LlmGatewayService gatewayService,
                         OpenAiCompatibleClient openAiClient,
                         CustomHttpLlmClient customHttpClient,
                         com.shimeta.tool.AgentToolRegistry toolRegistry,
                         AgentToolSecretService toolSecretService,
                         PlatformToolService platformToolService,
                         KnowledgeBaseService knowledgeBaseService,
                         ResourceAuthorizationService resourceAuthorizationService,
                         @Autowired(required = false) ChatModel chatModel) {
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
        this.chatClient = (chatModel != null) ? ChatClient.builder(chatModel).build() : null;
    }

    /**
     * 流式输出的接收方：模型生成的文字通过 onDelta 边生成边推送；cancelled 返回 true 时停止生成（调用方断开、输出被拦截）。
     * 走不了流式的通道（自定义 HTTP、兜底回复）不会调用 onDelta，由调用方在拿到完整回复后自行输出。
     */
    public interface StreamSink {
        void onDelta(String text);

        boolean cancelled();
    }

    /** 流式输出已经推送了部分内容后通道失败：此时不能再重试或切换降级通道（会与已推送的内容拼在一起） */
    public static class StreamInterruptedException extends RuntimeException {
        public StreamInterruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ChatResponse chat(ChatRequest request) {
        return chat(request, null);
    }

    private com.shimeta.mcp.McpServerService mcpServerService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMcpServerService(com.shimeta.mcp.McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    private com.shimeta.skill.SkillService skillService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSkillService(com.shimeta.skill.SkillService skillService) {
        this.skillService = skillService;
    }

    static boolean isMcpToolName(String name) {
        return name != null && name.startsWith("mcp_");
    }

    /** 已路由到模型网关的通道，但通道失败或没有返回内容：消息里带具体原因，不能再回退到 Spring AI 默认客户端 */
    private static class ChannelCallException extends IllegalStateException {
        ChannelCallException(String message) {
            super(message);
        }
    }

    /** 一次智能体对话的路由情况：是否走了网关通道、是否切到了备用通道，以及拿不到用量时用的输入估算值 */
    private static class RouteTrace {
        boolean routed;
        String failoverFrom;
        int estimatedPromptTokens;
        /** 最近一次通道调用失败的原因（如模型服务返回的错误），用于告诉用户具体出了什么问题 */
        String lastError;
        /** 本次路由到的主通道名称 */
        String channel;
        /** 智能体选的通道不能用、已改用平台默认时的说明 */
        String routeNotice;
    }

    /**
     * 解析智能体这次对话的路由。没选自己的通道时和以前一样走平台默认路由；
     * 选了时按选择解析，选的通道不能用会改用平台默认并带上说明
     */
    private java.util.Optional<LlmGatewayService.AgentRoute> resolveAgentRoute(com.shimeta.model.AgentRouteChoice choice) {
        if (choice == null || !choice.isCustom()) {
            String model = choice != null ? choice.getModelName() : null;
            return gatewayService.resolveRoute(model).map(route -> new LlmGatewayService.AgentRoute(route, model, null));
        }
        return gatewayService.resolveAgentRoute(choice);
    }

    /** 通道全部失败时给用户看的说明：带上通道返回的具体原因 */
    static String channelFailureMessage(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return "模型通道调用失败，请到「模型网关」检查通道连通性或稍后重试";
        }
        String reason = lastError.length() > 300 ? lastError.substring(0, 300) + "…" : lastError;
        return "模型通道调用失败：" + reason
                + "。请到「模型网关」检查该通道；如果是输入过长，请把通道的「上下文窗口」设为模型实际支持的长度";
    }

    /** 粗略估算 Token：中日韩字符每字约 1 个，其余每 4 个字符约 1 个。只在通道没有返回用量时使用 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.codePoints().filter(Character::isIdeographic).count();
        long others = text.codePoints().count() - cjk;
        return (int) (cjk + (others + 3) / 4);
    }

    /** 本次对话检索命中的 FAQ 附图与视频：在拼装知识库上下文时记下，chat() 结束前取出并清空 */
    private static final ThreadLocal<List<String>> RETRIEVED_FAQ_IMAGES = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> RETRIEVED_FAQ_VIDEOS = new ThreadLocal<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private ConversationCompactionService compactionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCompactionService(ConversationCompactionService compactionService) {
        this.compactionService = compactionService;
    }

    public ChatResponse chat(ChatRequest request, StreamSink sink) {
        long startTime = System.currentTimeMillis();
        RETRIEVED_FAQ_IMAGES.remove();
        RETRIEVED_FAQ_VIDEOS.remove();

        boolean enableTrace = !Boolean.FALSE.equals(request.getTraceEnabled());
        ChatTraceContext existingTrace = ChatTraceContext.current();
        boolean ownTrace = enableTrace && existingTrace == null;
        ChatTraceContext traceCtx = enableTrace
                ? (ownTrace ? ChatTraceContext.begin(request.getAgentId(), null, null) : existingTrace)
                : null;

        String prepareStepId = traceCtx != null ? traceCtx.startStep("PREPARE", "权限与上下文准备", null, null) : null;
        try {
            return executeChat(request, sink, startTime, traceCtx, prepareStepId);
        } finally {
            if (ownTrace) {
                ChatTraceContext.clear();
            }
        }
    }

    private ChatResponse executeChat(ChatRequest request, StreamSink sink, long startTime,
                                     ChatTraceContext traceCtx, String prepareStepId) {
        Agent agent;
        try {
            agent = agentService.getById(request.getAgentId())
                    .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + request.getAgentId()));
            if (traceCtx != null) {
                traceCtx.setAgentMeta(agent.getId(), agent.getName());
            }

            com.shimeta.security.CurrentActor actor = com.shimeta.security.CurrentActor.get();
            if (actor != null && !resourceAuthorizationService.canRunAgent(actor, agent)) {
                throw new IllegalStateException("权限不足：无权运行或调用该智能体");
            }

            // 强校验智能体绑定的知识库依赖权限：
            // 智能体拥有者对所绑定的每一个知识库必须拥有有效的 USE 权限；
            // 若依赖失效或授权已撤销，在此立即阻断，严禁调用 LLM 或 Dify，且不产生任何数据库会话写入与外部调用！
            resourceAuthorizationService.checkAgentKnowledgeBaseDependencies(agent);
        } catch (RuntimeException e) {
            if (traceCtx != null && prepareStepId != null) {
                traceCtx.failStep(prepareStepId, e.getMessage(), null);
            }
            throw e;
        }

        // 上下文记忆：只带最近 N 轮问答（0 = 不带历史），无论调用方传了多少条
        request.setHistory(recentTurns(request.getHistory(), agent.resolveMemoryTurns(), request.getMessage()));

        String userMessage = request.getMessage();
        String reply;
        String executionModel = agent.getModelName() != null && !agent.getModelName().isBlank()
                ? agent.getModelName()
                : "未指定";
        int promptTokens = 0;
        int cachedPromptTokens = 0;
        int completionTokens = 0;
        boolean realModelReply = false;
        String[] routedModel = { executionModel };
        String[] toolCalledHolder = { null };
        if (platformToolService != null) {
            toolRegistry.setPlatformConfigs(platformToolService.runtimeConfigs());
            toolRegistry.setDisabledPlatformTools(platformToolService.disabledToolCodes());
            toolRegistry.setHttpTools(platformToolService.httpRuntimeSpecs());
        }
        // 智能体勾选了 MCP 工具（函数名以 mcp_ 开头）才去查，其它对话不多这一次查询
        if (mcpServerService != null && request.getEnabledTools() != null
                && request.getEnabledTools().stream().anyMatch(AiChatService::isMcpToolName)) {
            toolRegistry.setMcpTools(mcpServerService.runtimeSpecs());
        }
        List<java.util.Map<String, Object>> tools = toolRegistry.getToolDefinitions(request.getEnabledTools());
        // 关联了技能：系统提示里列出技能目录，再加一个 load_skill 工具让模型按需读取完整步骤
        com.shimeta.skill.SkillSession skillSession = null;
        if (skillService != null && agent.getSkillIds() != null && !agent.getSkillIds().isEmpty()) {
            List<com.shimeta.skill.SkillSpec> specs = skillService.runtimeSpecs(agent.getSkillIds());
            if (!specs.isEmpty()) {
                java.util.Set<String> available = new java.util.HashSet<>();
                for (java.util.Map<String, Object> tool : tools) {
                    if (tool.get("function") instanceof java.util.Map<?, ?> fn && fn.get("name") != null) {
                        available.add(String.valueOf(fn.get("name")));
                    }
                }
                skillSession = new com.shimeta.skill.SkillSession(specs, available, skillService::recordLoad);
                tools = new java.util.ArrayList<>(tools);
                tools.add(skillSession.toolDefinition());
                toolRegistry.setSkillSession(skillSession);
            }
        }
        // 模型自主检索：不在提问前检索，改为注册 search_knowledge 工具，由模型决定什么时候查、查什么。
        // 通道不支持工具调用时 invokeViaGateway 会退回先检索后回答
        KnowledgeSearchSession knowledgeSession = null;
        if (agent.isAgenticKnowledge() && knowledgeBaseService != null
                && agent.getKnowledgeBaseIds() != null && !agent.getKnowledgeBaseIds().isEmpty()) {
            java.util.Map<String, String> names = knowledgeBaseService.searchableNames(agent.getKnowledgeBaseIds());
            if (!names.isEmpty()) {
                knowledgeSession = new KnowledgeSearchSession(names, knowledgeBaseService::buildRetrieval);
                tools = new java.util.ArrayList<>(tools);
                tools.add(knowledgeSession.toolDefinition());
                toolRegistry.setKnowledgeSession(knowledgeSession);
            }
        }
        toolRegistry.setCurrentContextAgentId(agent.getId());
        // 启用了 DeepSeek 联网搜索时才取 Key（工具管理页单独填写的优先，否则用模型网关 DeepSeek 通道的）
        if (platformToolService != null && hasTool(tools, "deepseek_web_search")) {
            toolRegistry.setCurrentContextDeepSeekKey(platformToolService.resolveDeepSeekSearchKey());
        }

        // 当前请求 Key 优先，否则使用该智能体在数据库中加密保存的 Key。
        String bochaKey = firstNonBlank(
                extractBochaApiKey(request.getToolConfigs()),
                toolSecretService.getBochaApiKey(agent.getId())
        );
        if (bochaKey != null && !bochaKey.isBlank()) {
            toolRegistry.setCurrentContextBochaApiKey(bochaKey);
        }

        RouteTrace trace = new RouteTrace();
        StringBuilder streamedText = new StringBuilder();
        StreamSink trackedSink = sink == null ? null : new StreamSink() {
            @Override
            public void onDelta(String text) {
                streamedText.append(text);
                sink.onDelta(text);
            }

            @Override
            public boolean cancelled() {
                return sink.cancelled();
            }
        };

        // 调试页带了本窗口的通道草稿就用草稿，否则用智能体已发布的选择
        boolean draftRoute = request.getRouteDraft() != null;
        com.shimeta.model.AgentRouteChoice routeChoice = draftRoute
                ? request.getRouteDraft() : com.shimeta.model.AgentRouteChoice.of(agent);

        // 解析并校验自定义提示词变量（必填且无默认值时直接返回明确提示，不浪费模型调用）
        java.util.Map<String, String> resolvedInputs = com.shimeta.model.PromptVariable.resolveInputs(
                agent.getPromptVariables(), request.getInputs(), true);

        // 仅当智能体开启了「记忆压缩」且续接已有会话时读取早期摘要（默认关闭，零额外查询）
        String memorySummary = (agent.isMemoryCompressionEnabled()
                && request.getConversationId() != null && !request.getConversationId().isBlank())
                ? conversationService.getMemorySummary(agent.getId(), request.getConversationId(), request.getAccount()).orElse(null)
                : null;

        if (traceCtx != null && prepareStepId != null) {
            int kbCount = agent.getKnowledgeBaseIds() != null ? agent.getKnowledgeBaseIds().size() : 0;
            int skillCount = agent.getSkillIds() != null ? agent.getSkillIds().size() : 0;
            int toolCount = tools != null ? tools.size() : 0;
            int historyCount = request.getHistory() != null ? request.getHistory().size() : 0;
            java.util.Map<String, Object> prepDetail = new java.util.LinkedHashMap<>();
            prepDetail.put("memoryTurnsConfig", agent.resolveMemoryTurns());
            prepDetail.put("memoryCompression", agent.isMemoryCompressionEnabled());
            if (memorySummary != null) {
                prepDetail.put("memorySummaryChars", memorySummary.length());
            }
            prepDetail.put("historyMessages", historyCount);
            prepDetail.put("knowledgeBaseCount", kbCount);
            prepDetail.put("knowledgeMode", agent.isAgenticKnowledge() ? "模型自主检索" : "先检索后回答");
            prepDetail.put("toolCount", toolCount);
            prepDetail.put("skillCount", skillCount);
            prepDetail.put("draftRoute", draftRoute);
            if (!resolvedInputs.isEmpty()) {
                prepDetail.put("promptVariables", resolvedInputs);
            }
            traceCtx.finishStep(prepareStepId,
                    String.format("历史 %d 条%s · 知识库 %d 个 · 工具 %d 个 · 技能 %d 个%s",
                            historyCount,
                            memorySummary != null ? "+记忆摘要" : "",
                            kbCount, toolCount, skillCount,
                            resolvedInputs.isEmpty() ? "" : " · 变量 " + resolvedInputs.size() + " 个"),
                    prepDetail);
        }

        try {
            OpenAiCompatibleClient.ChatResult routed = invokeViaGateway(agent, routeChoice, userMessage, request.getHistory(),
                    request.getGeneration(), request.getPrompt(), request.getInputs(), memorySummary, tools, request.getEnabledTools(), routedModel, trackedSink, trace);
            if (routed != null && routed.content() != null && !routed.content().isBlank()) {
                reply = routed.content();
                executionModel = routedModel[0];
                promptTokens = Math.max(0, routed.promptTokens());
                cachedPromptTokens = Math.max(0, routed.cachedPromptTokens());
                completionTokens = Math.max(0, routed.completionTokens());
                if (promptTokens + completionTokens == 0 && routed.totalTokens() > 0) {
                    completionTokens = routed.totalTokens();
                }
                // 流式中途断开时通道不会返回用量，这部分 Token 已经消耗，按文本长度估算
                if (promptTokens + completionTokens == 0 && trackedSink != null && trackedSink.cancelled()) {
                    promptTokens = trace.estimatedPromptTokens;
                    completionTokens = estimateTokens(reply);
                }
                realModelReply = true;
                if (routed.toolCalled() != null) {
                    toolCalledHolder[0] = routed.toolCalled();
                }
            } else if (trackedSink != null && trackedSink.cancelled()) {
                throw new StreamInterruptedException("调用方已断开连接", null);
            } else if (trace.routed) {
                throw new ChannelCallException(routed == null
                        ? channelFailureMessage(trace.lastError)
                        : "模型没有返回内容，请重试或换一种问法；如果持续出现，请到「模型网关」检查该通道");
            } else if (chatClient != null) {
                log.info("Invoking Spring AI ChatClient for Agent: [{}] with model: [{}]", agent.getName(), executionModel);
                
                String summaryBlock = (memorySummary != null && !memorySummary.isBlank())
                        ? "【早期对话记忆摘要】\n" + memorySummary.trim() : null;
                PromptLayout layout = buildPromptLayout(request.getPrompt(), agent, userMessage, request.getEnabledTools(),
                        null, summaryBlock, true, request.getInputs());
                var clientRequest = chatClient.prompt()
                        .system(layout.system());

                // 添加历史记录
                if (request.getHistory() != null && !request.getHistory().isEmpty()) {
                    for (ChatMessage msg : request.getHistory()) {
                        if ("user".equalsIgnoreCase(msg.getRole())) {
                            clientRequest = clientRequest.user(msg.getContent());
                        }
                    }
                }

                clientRequest = clientRequest.user(layout.user());
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
                    throw new ChannelCallException("模型没有返回内容，请重试或换一种问法；如果持续出现，请到「模型网关」检查该通道");
                }
            } else {
                throw new ChannelCallException(NO_CHANNEL_MESSAGE);
            }
        } catch (StreamInterruptedException ex) {
            // 输出到一半中断：拿不到用量，按文本长度估算后计入，算一次失败调用
            agentService.recordInvocation(agent.getId(), System.currentTimeMillis() - startTime,
                    trace.estimatedPromptTokens, estimateTokens(streamedText.toString()), false, trace.failoverFrom != null, 0);
            throw ex;
        } catch (Exception ex) {
            log.warn("模型调用失败: {}", ex.getMessage());
            // 下面直接抛错，走不到末尾的统计，这里先记一次失败调用
            agentService.recordInvocation(agent.getId(), System.currentTimeMillis() - startTime,
                    0, 0, false, trace.failoverFrom != null, 0);
            throw modelFailure(ex);
        } finally {
            toolRegistry.clearCurrentContext();
        }
        List<String> skillsLoaded = skillSession != null ? skillSession.loadedNames() : List.of();
        List<String> knowledgeSearches = knowledgeSession != null ? knowledgeSession.queries() : List.of();
        List<String> faqImages = RETRIEVED_FAQ_IMAGES.get();
        List<String> faqVideos = RETRIEVED_FAQ_VIDEOS.get();
        if ((faqImages == null || faqImages.isEmpty()) && (faqVideos == null || faqVideos.isEmpty()) && knowledgeSession != null) {
            faqImages = knowledgeSession.faqImages();
            faqVideos = knowledgeSession.faqVideos();
        }
        RETRIEVED_FAQ_IMAGES.remove();
        RETRIEVED_FAQ_VIDEOS.remove();

        reply = cleanAnswer(reply);

        long latencyMs = System.currentTimeMillis() - startTime;
        int tokens = promptTokens + completionTokens;
        agentService.recordInvocation(agent.getId(), latencyMs, promptTokens, completionTokens, realModelReply,
                trace.failoverFrom != null, cachedPromptTokens);
        // 备用通道只是临时顶替，不能把智能体自己的模型设置改成备用模型；
        // 调试草稿和智能体自选的通道都由用户明确指定了模型，不能被这里改写
        if (realModelReply && trace.failoverFrom == null && !draftRoute && !routeChoice.isCustom()) {
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
        response.setImages(faqImages);
        response.setVideos(faqVideos);
        response.setSkillsLoaded(skillsLoaded);
        response.setKnowledgeSearches(knowledgeSearches);
        response.setPromptTokens(promptTokens);
        response.setCachedPromptTokens(cachedPromptTokens);
        response.setCompletionTokens(completionTokens);
        response.setFailoverFrom(realModelReply ? trace.failoverFrom : null);
        response.setChannel(trace.channel);
        response.setRouteNotice(trace.routeNotice);
        response.setDegraded(!realModelReply);
        response.setSource("MODEL");
        if (traceCtx != null) {
            ChatTrace chatTrace = traceCtx.snapshotOk(
                    executionModel,
                    trace.channel,
                    realModelReply ? trace.failoverFrom : null,
                    trace.routeNotice,
                    promptTokens,
                    cachedPromptTokens,
                    completionTokens
            );
            response.setTrace(chatTrace);
            traceCtx.setSerializedTrace(serializeTrace(chatTrace));
        }
        AgentConversation conversation = conversationService.appendTurn(
                agent.getId(),
                request.getConversationId(),
                request.getAccount(),
                userMessage,
                reply,
                executionModel,
                latencyMs,
                tokens,
                request.getNewConversationId()
        );
        response.setConversationId(conversation.getId());
        if (realModelReply && compactionService != null && agent.isMemoryCompressionEnabled()) {
            compactionService.compactAgentAsync(agent, conversation.getId());
        }
        return response;
    }

    private static String serializeTrace(ChatTrace trace) {
        if (trace == null) {
            return null;
        }
        try {
            return TRACE_MAPPER.writeValueAsString(trace);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取最近 turns 轮问答（每轮 = 用户 + 助手两条消息）。
     * 调用方把本次问题也放进了历史末尾时先去掉（本次问题会单独发送，否则模型会看到两遍）；
     * 结果不以助手消息开头，避免模型看到没有提问的回答。
     */
    static List<ChatMessage> recentTurns(List<ChatMessage> history, int turns, String currentMessage) {
        if (history == null || history.isEmpty() || turns <= 0) {
            return List.of();
        }
        List<ChatMessage> items = history.stream()
                .filter(m -> m != null && m.getRole() != null && m.getContent() != null)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (!items.isEmpty() && currentMessage != null) {
            ChatMessage last = items.get(items.size() - 1);
            if ("user".equalsIgnoreCase(last.getRole()) && currentMessage.trim().equals(last.getContent().trim())) {
                items.remove(items.size() - 1);
            }
        }
        int from = Math.max(0, items.size() - turns * 2);
        while (from < items.size() && !"user".equalsIgnoreCase(items.get(from).getRole())) {
            from++;
        }
        return List.copyOf(items.subList(from, items.size()));
    }

    // ==================== 平台 AI 助手（不绑定智能体，走模型网关默认路由） ====================

    private static final int ASSISTANT_HISTORY_LIMIT = 12;

    /**
     * 平台 AI 助手对话：不绑定任何智能体，使用模型网关解析出的默认路由；主通道失败时按策略切换降级通道。
     */
    public ChatResponse assistantChat(String userMessage, List<ChatMessage> history) {
        long start = System.currentTimeMillis();
        ChatResponse response = new ChatResponse();
        response.setAgentName("AgentMatrix 助手");
        response.setSource("assistant");
        response.setTimestamp(java.time.LocalDateTime.now());

        List<ChatMessage> recent = history == null ? List.of()
                : history.subList(Math.max(0, history.size() - ASSISTANT_HISTORY_LIMIT), history.size());
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(0.5);
        String[] routedModel = new String[1];

        var routeOpt = gatewayService.resolveRoute(null);
        if (routeOpt.isEmpty()) {
            response.setReply("当前还没有可用的大模型通道。请先到「模型网关 → 大语言模型」中添加并启用一个模型通道，然后再来和我对话。");
            response.setDegraded(true);
            response.setLatencyMs(System.currentTimeMillis() - start);
            return response;
        }

        var route = routeOpt.get();
        String instruction = com.shimeta.assistant.AssistantPrompts.chatMode();
        var messages = OpenAiCompatibleClient.toMessages(instruction, userMessage, recent);
        OpenAiCompatibleClient.ChatResult result = callProvider(route.primary(), route.primaryKey(), null, generation,
                route.timeoutMs(), route.maxRetries(), messages, List.of(), routedModel, null);
        if (result == null && route.fallback() != null && gatewayService.hasKey(route.fallback())) {
            log.warn("Assistant primary channel [{}] failed, switching to fallback [{}]",
                    route.primary().getName(), route.fallback().getName());
            result = callProvider(route.fallback(), route.fallbackKey(), null, generation,
                    route.timeoutMs(), route.maxRetries(), messages, List.of(), routedModel, null);
        }

        response.setLatencyMs(System.currentTimeMillis() - start);
        response.setModel(routedModel[0]);
        if (result == null || result.content() == null || result.content().isBlank()) {
            response.setReply("模型通道暂时没有返回结果，请稍后重试，或到「模型网关」中检查通道的连通性。");
            response.setDegraded(true);
            return response;
        }
        response.setReply(cleanAnswer(result.content()));
        response.setTokensUsed(result.totalTokens());
        response.setDegraded(false);
        return response;
    }

    private OpenAiCompatibleClient.ChatResult invokeViaGateway(Agent agent, com.shimeta.model.AgentRouteChoice routeChoice,
                                                               String userMessage,
                                                               List<ChatMessage> history, ChatGeneration generation,
                                                               String livePrompt, java.util.Map<String, Object> inputs,
                                                               String memorySummary,
                                                               List<java.util.Map<String, Object>> tools,
                                                               List<String> enabledTools,
                                                               String[] routedModel, StreamSink sink, RouteTrace trace) {
        ChatGeneration effective = generation != null ? generation : fromAgent(agent);
        return resolveAgentRoute(routeChoice).map(agentRoute -> {
            LlmGatewayService.ResolvedRoute route = agentRoute.route();
            String requestedModel = agentRoute.model();
            trace.routed = true;
            trace.channel = route.primary().getName();
            trace.routeNotice = agentRoute.notice();
            ChatTraceContext traceCtx = ChatTraceContext.current();
            if (traceCtx != null) {
                traceCtx.setRouteMeta(trace.channel, requestedModel, null, trace.routeNotice);
            }
            com.shimeta.model.LlmProvider fallback = route.fallback() != null && gatewayService.hasKey(route.fallback())
                    ? route.fallback() : null;
            // 通道不支持工具调用时不发工具定义，也不在提示词里写工具指引；主备通道分别判断
            boolean primaryTools = LlmGatewayService.supportsTools(route.primary());
            boolean fallbackTools = fallback != null && LlmGatewayService.supportsTools(fallback);
            // 模型自主检索要求主备通道都能调用工具（切到备用通道时用的是同一份提示词），否则退回先检索后回答
            KnowledgeSearchSession knowledge = toolRegistry.currentKnowledgeSession();
            boolean agenticKnowledge = knowledge != null && primaryTools && (fallback == null || fallbackTools);
            List<java.util.Map<String, Object>> effectiveTools = tools;
            if (knowledge != null && !agenticKnowledge) {
                log.info("Agent [{}] uses model-driven knowledge search, but the channel cannot call tools; falling back to retrieving before answering",
                        agent.getName());
                effectiveTools = tools == null ? null : tools.stream().filter(t -> !(t.get("function") instanceof java.util.Map<?, ?> fn
                        && KnowledgeSearchSession.TOOL_NAME.equals(fn.get("name")))).toList();
                toolRegistry.setKnowledgeSession(null);
            }
            List<java.util.Map<String, Object>> primaryToolDefs = primaryTools || effectiveTools == null ? effectiveTools : List.of();
            List<java.util.Map<String, Object>> fallbackToolDefs = fallbackTools ? effectiveTools : List.of();
            if (!primaryTools && tools != null && !tools.isEmpty()) {
                log.info("Channel [{}] does not support tool calls, skipping {} tool definitions",
                        route.primary().getName(), tools.size());
            }
            List<String> layoutTools = primaryTools ? enabledTools : null;
            // 技能靠 load_skill 工具加载，通道不支持工具调用时也不放技能目录
            com.shimeta.skill.SkillSession skills = primaryTools ? toolRegistry.currentSkillSession() : null;
            String skillCatalog = skills != null ? skills.catalog() : null;
            String summaryAppendix = (memorySummary != null && !memorySummary.isBlank())
                    ? "【早期对话记忆摘要】\n" + memorySummary.trim() : null;
            String systemAppendix = joinNonBlank(joinNonBlank(agenticKnowledge ? knowledge.guidance() : null, skillCatalog), summaryAppendix);
            boolean retrieveFirst = !agenticKnowledge;

            // 主备通道都配置了上下文窗口时取较小的那个，切到备用通道时同一份消息也放得下
            ContextWindowPlanner.Plan plan = ContextWindowPlanner.plan(
                    smallerWindow(route.primary(), fallback), effective.getMaxTokens());
            ChatGeneration callGeneration = effective;
            List<ChatMessage> fittedHistory = history;
            PromptLayout layout;
            if (plan == null) {
                layout = buildPromptLayout(livePrompt, agent, userMessage, layoutTools, null, systemAppendix, retrieveFirst, inputs);
            } else {
                int toolTokens = Math.max(estimateTokens(toolsJson(primaryToolDefs)), estimateTokens(toolsJson(fallbackToolDefs)));
                // 知识库检索先给技能正文让出一部分（最多输入预算的四分之一，不超过最长那个技能）
                int skillReserve = skills == null ? 0 : Math.min(skills.maxContentTokens(),
                        Math.max(0, plan.inputBudget() / 4));
                layout = buildPromptLayout(livePrompt, agent, userMessage, layoutTools,
                        bare -> plan.inputBudget() - toolTokens - skillReserve
                                - ContextWindowPlanner.messageTokens(bare.system())
                                - ContextWindowPlanner.messageTokens(bare.user())
                                - KNOWLEDGE_FRAME_TOKENS, systemAppendix, retrieveFirst, inputs);
                int used = toolTokens + ContextWindowPlanner.messageTokens(layout.system())
                        + ContextWindowPlanner.messageTokens(layout.user());
                if (skills != null) {
                    // 技能正文和历史消息分剩下的空间：技能最多拿一半
                    int reserve = Math.min(skills.maxContentTokens(), Math.max(0, (plan.inputBudget() - used) / 2));
                    skills.setTokenBudget(reserve);
                    used += reserve;
                }
                if (agenticKnowledge) {
                    // 模型自己检索的资料在工具调用过程中才加进来，同样先留出空间：最多拿剩下的一半
                    int reserve = Math.min(KnowledgeSearchSession.DEFAULT_TOTAL_TOKENS, Math.max(0, (plan.inputBudget() - used) / 2));
                    knowledge.setTokenBudget(reserve);
                    used += reserve;
                }
                fittedHistory = ContextWindowPlanner.fitHistory(history, plan.inputBudget() - used);
                callGeneration = effective.copy();
                callGeneration.setMaxTokens(plan.outputTokens());
                log.info("Context window {}: output {} tokens, input ~{} / {} tokens, history {} -> {} messages",
                        plan.window(), plan.outputTokens(), used + ContextWindowPlanner.historyTokens(fittedHistory),
                        plan.inputBudget(), history == null ? 0 : history.size(), fittedHistory == null ? 0 : fittedHistory.size());
            }
            var messages = OpenAiCompatibleClient.toMessages(
                    layout.system(),
                    layout.user(),
                    fittedHistory
            );
            trace.estimatedPromptTokens = messages.stream()
                    .mapToInt(m -> estimateTokens(String.valueOf(m.get("content"))))
                    .sum() + estimateTokens(toolsJson(primaryToolDefs));
            OpenAiCompatibleClient.ChatResult result = callProvider(route.primary(), route.primaryKey(),
                    requestedModel, callGeneration, route.timeoutMs(), route.maxRetries(), messages, primaryToolDefs,
                    routedModel, sink, trace);
            if (result != null) {
                return result;
            }
            if (fallback != null) {
                log.warn("Primary LLM channel [{}] failed, switching to fallback [{}]",
                        route.primary().getName(), fallback.getName());
                trace.failoverFrom = route.primary().getName();
                trace.channel = fallback.getName();
                if (traceCtx != null) {
                    traceCtx.setRouteMeta(fallback.getName(), null, trace.failoverFrom, trace.routeNotice);
                    traceCtx.recordInstantStep("ROUTE", "主备通道自动切换", "ok",
                            route.primary().getName() + " → " + fallback.getName(),
                            java.util.Map.of("fromChannel", route.primary().getName(),
                                    "toChannel", fallback.getName(),
                                    "reason", trace.lastError != null ? trace.lastError : "主通道调用失败"));
                }
                // 自己选的备用通道用它的默认模型（选的模型属于主通道）；跟随平台时和以前一样按模型名匹配
                boolean chosenFallback = routeChoice != null && fallback.getId().equals(routeChoice.getFallbackProviderId());
                return callProvider(fallback, route.fallbackKey(),
                        chosenFallback ? null : requestedModel, callGeneration, route.timeoutMs(), route.maxRetries(),
                        messages, fallbackToolDefs, routedModel, sink, trace);
            }
            return null;
        }).orElse(null);
    }

    /** 知识库内容外面的说明文字和每条资料的序号、来源，检索预算里不包含这部分 */
    static final int KNOWLEDGE_FRAME_TOKENS = 150;

    private static Integer smallerWindow(com.shimeta.model.LlmProvider primary, com.shimeta.model.LlmProvider fallback) {
        Integer a = primary != null ? primary.getContextWindow() : null;
        Integer b = fallback != null ? fallback.getContextWindow() : null;
        if (a == null || a <= 0) {
            return b != null && b > 0 ? b : null;
        }
        return b != null && b > 0 ? Math.min(a, b) : a;
    }

    private static String toolsJson(List<java.util.Map<String, Object>> tools) {
        return tools == null || tools.isEmpty() ? "" : String.valueOf(tools);
    }

    /**
     * 一次请求的提示词。模型的提示词缓存按前缀匹配，所以固定内容放 system，
     * 每次都会变的当前时间和检索结果放到最后一条 user 消息里，否则 system 和历史消息都复用不上。
     */
    public record PromptLayout(String system, String user) {}

    public PromptLayout buildPromptLayout(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools) {
        return buildPromptLayout(rawPrompt, agent, userMessage, enabledTools, null);
    }

    /**
     * @param knowledgeBudget 给定不带知识库内容的提示词，返回知识库最多能占多少 token；为空时用默认上限。
     *                        预算太小（放不下一条资料）时不检索
     */
    PromptLayout buildPromptLayout(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools,
                                   java.util.function.ToIntFunction<PromptLayout> knowledgeBudget) {
        return buildPromptLayout(rawPrompt, agent, userMessage, enabledTools, knowledgeBudget, null);
    }

    /** @param skillCatalog 技能目录（名称和适用场景），放在系统提示最后；为空时不放 */
    PromptLayout buildPromptLayout(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools,
                                   java.util.function.ToIntFunction<PromptLayout> knowledgeBudget, String skillCatalog) {
        return buildPromptLayout(rawPrompt, agent, userMessage, enabledTools, knowledgeBudget, skillCatalog, true);
    }

    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + "\n\n" + b;
        }
        return hasA ? a : (hasB ? b : null);
    }

    /**
     * @param systemAppendix 放在系统提示最后的内容（知识库检索说明、技能目录）；为空时不放
     * @param retrieveFirst  是否在提问前检索知识库；模型自主检索时为 false
     */
    PromptLayout buildPromptLayout(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools,
                                   java.util.function.ToIntFunction<PromptLayout> knowledgeBudget, String systemAppendix,
                                   boolean retrieveFirst) {
        return buildPromptLayout(rawPrompt, agent, userMessage, enabledTools, knowledgeBudget, systemAppendix, retrieveFirst, null);
    }

    /**
     * @param systemAppendix 放在系统提示最后的内容（知识库检索说明、技能目录）；为空时不放
     * @param retrieveFirst  是否在提问前检索知识库；模型自主检索时为 false
     * @param inputs         本轮对话传入的自定义提示词变量值；为空时按变量默认值回退
     */
    PromptLayout buildPromptLayout(String rawPrompt, Agent agent, String userMessage, List<String> enabledTools,
                                   java.util.function.ToIntFunction<PromptLayout> knowledgeBudget, String systemAppendix,
                                   boolean retrieveFirst, java.util.Map<String, Object> inputs) {
        String skillCatalog = systemAppendix;
        String base = firstNonBlank(rawPrompt, agent.getSystemPrompt(), "你是一个通用智能助手。");

        // 1. 获取当前系统真实时间与星期 (标准北京时间 Asia/Shanghai，直接读取服务端时钟，不依赖是否开启时间工具)
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);

        // 2. 单遍替换内置系统变量 + 自定义变量占位符 (支持 Dify 及通用模版变量规范，避免二次替换)
        java.util.Map<String, String> templateVars = new java.util.LinkedHashMap<>(
                com.shimeta.model.PromptVariable.resolveInputs(agent.getPromptVariables(), inputs, false));
        templateVars.put("system_time", timeStr + " (" + weekday + ")");
        templateVars.put("sys.time", timeStr);
        templateVars.put("time", timeStr);
        templateVars.put("sys.date", dateStr);
        templateVars.put("date", dateStr);
        templateVars.put("today", dateStr + " " + weekday);
        templateVars.put("input", userMessage != null ? userMessage : "");
        String processed = com.shimeta.model.PromptVariable.renderTemplate(base, templateVars);

        // 3. 构建高优先级系统基准时间环境约束（Grounding Time Context），这里不放具体时间，保持 system 固定
        StringBuilder timeContext = new StringBuilder(
                "【系统基准环境】\n" +
                "- 所在时区：Asia/Shanghai (GMT+8 / 北京时间)\n" +
                "【时效性准则】用户消息开头的【当前时间】是您所在物理世界的绝对真实系统时间。在处理涉及“今天”、“现在”、“当前”、“今年”、“本月”或日期相对计算时，必须严格以此时间为准。若调用了联网检索工具（如博查搜索），检索到的互联网文章可能包含历史发布的旧快照，切勿将历史网页的发布时间误认为当前的真实日期！"
        );

        // 若挂载了扩展工具，注入工具调用路由规范，消除模型对时间工具与联网搜索的二义性竞争
        // MCP 工具名是服务自己起的，可能碰巧带 time、检索这些字眼，下面按名称猜内置工具的指引不能算上它们
        List<String> builtinToolNames = enabledTools == null ? List.of()
                : enabledTools.stream().filter(t -> !isMcpToolName(t)).toList();
        if (!builtinToolNames.isEmpty()) {
            boolean hasTime = builtinToolNames.stream().anyMatch(t -> t.contains("时间") || t.contains("time") || t.contains("时区") || t.contains("星期"));
            boolean hasDeepSeekSearch = builtinToolNames.stream().anyMatch(t -> t.contains("DeepSeek") || t.contains("deepseek"));
            boolean hasBocha = builtinToolNames.stream().anyMatch(t -> t.contains("bocha")
                    || ((t.contains("联网") || t.contains("检索")) && !t.contains("DeepSeek") && !t.contains("deepseek")));

            StringBuilder toolGuide = new StringBuilder("\n\n【工具调用指引规范】\n");
            if (hasTime) {
                toolGuide.append("- 当前智能体已装配时间工具集（包含获取当前时间、星期几计算器等）。当用户询问“今天多少号”、“今天几号”、“现在几点”、“当前日期”等本地时序问题时，可直接参考系统基准时间或优先调用 `time_get_current_time` 工具，严禁使用联网搜索查询当前本地系统时间！\n");
            }
            if (hasBocha || hasDeepSeekSearch) {
                List<String> searchTools = new java.util.ArrayList<>();
                if (hasBocha) {
                    searchTools.add("`bocha_web_search`");
                }
                if (hasDeepSeekSearch) {
                    searchTools.add("`deepseek_web_search`");
                }
                toolGuide.append("- 联网检索工具（").append(String.join("、", searchTools))
                        .append("）仅用于查询外部最新新闻、实时天气或全网事实，切勿使用联网检索来查问当前的本地系统时间！");
                if (searchTools.size() > 1) {
                    toolGuide.append("同一问题只需调用其中一个联网检索工具。");
                }
                toolGuide.append("\n");
            }
            timeContext.append(toolGuide.toString().trim());
        }

        // 注入到系统指令头部，赋予模型确定性的时序与工具调度认知
        String system = timeContext + "\n\n" + processed;
        if (skillCatalog != null && !skillCatalog.isBlank()) {
            system = system + "\n\n" + skillCatalog;
        }

        // 4. 当前时间和知识库检索片段（RAG 上下文）每次都会变，放进 user 消息
        String currentTime = String.format("【当前时间】%s（%s），北京时间 %s", dateStr, weekday, timeStr);
        if (!retrieveFirst) {
            return new PromptLayout(system, buildUserContent(currentTime, null, userMessage));
        }
        if (knowledgeBudget == null) {
            return new PromptLayout(system, buildUserContent(currentTime, retrieveKnowledge(agent, userMessage, null), userMessage));
        }
        PromptLayout bare = new PromptLayout(system, buildUserContent(currentTime, null, userMessage));
        int budget = knowledgeBudget.applyAsInt(bare);
        if (budget < ContextWindowPlanner.MIN_KNOWLEDGE_TOKENS) {
            if (agent.getKnowledgeBaseIds() != null && !agent.getKnowledgeBaseIds().isEmpty()) {
                log.warn("Context window too small for knowledge base content (budget {} tokens), skipping retrieval", budget);
            }
            return bare;
        }
        return new PromptLayout(system, buildUserContent(currentTime, retrieveKnowledge(agent, userMessage, budget), userMessage));
    }

    private static String buildUserContent(String currentTime, String knowledge, String userMessage) {
        StringBuilder content = new StringBuilder(currentTime).append("\n\n");
        if (knowledge != null && !knowledge.isBlank()) {
            content.append(knowledge.trim()).append("\n\n");
        }
        return content.append("【用户问题】\n").append(userMessage).toString();
    }

    private String retrieveKnowledge(Agent agent, String userMessage, Integer maxContextTokens) {
        KnowledgeBaseService.RetrievalContext retrieval = maxContextTokens == null
                ? knowledgeBaseService.buildRetrieval(agent.getKnowledgeBaseIds(), userMessage)
                : knowledgeBaseService.buildRetrieval(agent.getKnowledgeBaseIds(), userMessage, maxContextTokens);
        if (retrieval == null) {
            return "";
        }
        if (retrieval.faqImages() != null && !retrieval.faqImages().isEmpty()) {
            RETRIEVED_FAQ_IMAGES.set(retrieval.faqImages());
        }
        if (retrieval.faqVideos() != null && !retrieval.faqVideos().isEmpty()) {
            RETRIEVED_FAQ_VIDEOS.set(retrieval.faqVideos());
        }
        return retrieval.text();
    }

    private ChatGeneration fromAgent(Agent agent) {
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(agent.getTemperature());
        generation.setTopP(agent.getTopP());
        generation.setMaxTokens(agent.getMaxTokens());
        generation.setThinking(agent.resolveThinking());
        return generation;
    }

    private OpenAiCompatibleClient.ChatResult callProvider(com.shimeta.model.LlmProvider provider,
                                                           String apiKey, String requestedModel, ChatGeneration generation,
                                                           int timeoutMs, int maxRetries,
                                                           List<java.util.Map<String, Object>> messages,
                                                           List<java.util.Map<String, Object>> tools,
                                                           String[] routedModel, StreamSink sink) {
        return callProvider(provider, apiKey, requestedModel, generation, timeoutMs, maxRetries, messages, tools,
                routedModel, sink, null);
    }

    private OpenAiCompatibleClient.ChatResult callProvider(com.shimeta.model.LlmProvider provider,
                                                           String apiKey, String requestedModel, ChatGeneration generation,
                                                           int timeoutMs, int maxRetries,
                                                           List<java.util.Map<String, Object>> messages,
                                                           List<java.util.Map<String, Object>> tools,
                                                           String[] routedModel, StreamSink sink, RouteTrace trace) {
        String model = pickModel(provider, requestedModel);
        routedModel[0] = model;
        int attempts = Math.max(1, maxRetries + 1);
        // 记录是否已向调用方推送过内容：推送之后失败就不能再重试或降级，否则两次输出会拼在一起
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean(false);
        for (int i = 0; i < attempts; i++) {
            try {
                generation.setProviderProtocol(provider.getProtocol());
                if (provider.getProtocol() == com.shimeta.model.LlmProtocolType.CUSTOM_HTTP) {
                    log.info("Gateway routing agent chat via custom HTTP endpoint [{}]", provider.getName());
                    return customHttpClient.chat(provider, messages, generation, timeoutMs);
                }
                log.info("Gateway routing agent chat via [{}] model [{}] with {} tools", provider.getName(), model, tools != null ? tools.size() : 0);
                var customHeaders = LlmGatewayService.parseCustomHeaders(provider.getCustomConfig());
                boolean defaultWebSearch = LlmGatewayService.isWebSearchEnabled(provider);
                if (sink != null) {
                    return openAiClient.chatWithToolsStreaming(provider.getBaseUrl(), apiKey, model, customHeaders,
                            defaultWebSearch, messages, tools, toolRegistry, generation, timeoutMs,
                            text -> {
                                emitted.set(true);
                                sink.onDelta(text);
                            },
                            sink::cancelled, maxToolRounds());
                }
                return openAiClient.chatWithTools(provider.getBaseUrl(), apiKey, model, customHeaders, defaultWebSearch,
                        messages, tools, toolRegistry, generation, timeoutMs, maxToolRounds());
            } catch (Exception ex) {
                if (emitted.get()) {
                    throw new StreamInterruptedException("模型通道在输出过程中中断: " + ex.getMessage(), ex);
                }
                log.warn("Gateway channel [{}] attempt {} failed: {}", provider.getName(), i + 1, ex.getMessage());
                if (trace != null) {
                    trace.lastError = ex.getMessage();
                }
            }
        }
        return null;
    }

    private String pickModel(com.shimeta.model.LlmProvider provider, String requested) {
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

    private static boolean hasTool(List<java.util.Map<String, Object>> tools, String functionName) {
        return tools != null && tools.stream().anyMatch(tool -> tool.get("function") instanceof java.util.Map<?, ?> fn
                && functionName.equals(fn.get("name")));
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

    static final String NO_CHANNEL_MESSAGE = "当前没有可用的模型通道，请到「模型网关 → 大语言模型」启用一个通道并测试连通后再试";

    /**
     * 模型调用失败时给调用方的异常：消息里带具体原因（通道返回的错误、超时等），调试页和开放接口直接显示。
     * 不生成任何模拟或兜底回复
     */
    static ModelCallException modelFailure(Exception cause) {
        if (cause instanceof ModelCallException m) {
            return m;
        }
        if (cause instanceof ChannelCallException) {
            return new ModelCallException(cause.getMessage(), cause);
        }
        String reason = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage() : cause != null ? cause.getClass().getSimpleName() : null;
        if (reason == null) {
            return new ModelCallException(NO_CHANNEL_MESSAGE, null);
        }
        if (reason.length() > 300) {
            reason = reason.substring(0, 300) + "…";
        }
        return new ModelCallException("模型调用失败：" + reason, cause);
    }

    /**
     * 模型调用失败（没有可用通道、通道报错、超时、没有返回内容）。继承 IllegalStateException，
     * 已有按 IllegalStateException 处理的调用方照常显示消息；开放接口据此和权限类拒绝区分开
     */
    public static class ModelCallException extends IllegalStateException {
        public ModelCallException(String message, Throwable cause) {
            super(message, cause);
        }
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
