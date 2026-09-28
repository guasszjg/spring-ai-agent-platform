package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentDailyStat;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.DashboardStats;
import com.example.agentplatform.model.GatewayOverview;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeIndexVersion;
import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.model.LlmProviderView;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeIndexVersionRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.DifyConfigService;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.service.LlmGatewayService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 平台 AI 助手的只读工具（P1）。
 *
 * <p>约定：
 * <ul>
 *   <li>全部以当前用户身份执行，复用各 Service 已有的可见范围与 {@link ResourceAuthorizationService} 校验；</li>
 *   <li>返回给模型的数据是字段白名单，绝不包含密钥、密码、完整 API Key、通道地址与自定义请求头；</li>
 *   <li>结果统一为 {@code {ok: true, ...}}，失败时抛出业务异常，由注册表转成 {@code {ok: false, error}}。</li>
 * </ul>
 */
@Component
public class AssistantQueryTools {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    private static final int PROMPT_SUMMARY_CHARS = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentService agentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeIndexVersionRepository indexVersionRepository;
    private final LlmGatewayService gatewayService;
    private final EmbeddingConfigService embeddingConfigService;
    private final DifyConfigService difyConfigService;
    private final ResourceAuthorizationService authorizationService;
    private final AgentDailyStatRepository dailyStatRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssistantQueryTools(AgentService agentService,
                               KnowledgeBaseService knowledgeBaseService,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               KnowledgeIndexVersionRepository indexVersionRepository,
                               LlmGatewayService gatewayService,
                               EmbeddingConfigService embeddingConfigService,
                               DifyConfigService difyConfigService,
                               ResourceAuthorizationService authorizationService,
                               AgentDailyStatRepository dailyStatRepository) {
        this.agentService = agentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.indexVersionRepository = indexVersionRepository;
        this.gatewayService = gatewayService;
        this.embeddingConfigService = embeddingConfigService;
        this.difyConfigService = difyConfigService;
        this.authorizationService = authorizationService;
        this.dailyStatRepository = dailyStatRepository;
    }

    public List<AssistantTool> tools() {
        return List.of(
                new AssistantTool("list_agents", "查询智能体列表",
                        "查询当前用户可见的智能体列表。用户问\"有哪些智能体\"\"哪些在运行\"\"哪个调用最多\"时使用。",
                        schema(Map.of(
                                "keyword", prop("string", "名称、编码、描述、标签或创建人关键词，可选"),
                                "status", enumProp("运行状态，可选", "RUNNING", "IDLE", "DISABLED"),
                                "category", prop("string", "业务分类，可选"),
                                "scope", enumProp("范围：mine 只看我创建的，all 看我可见的全部（默认）", "mine", "all"),
                                "sort", enumProp("排序：updated 最近更新（默认），calls 累计调用次数", "updated", "calls"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        this::listAgents),
                new AssistantTool("get_agent_detail", "查看智能体配置",
                        "查看一个智能体的配置摘要：模型、提示词摘要、绑定的知识库与工具、状态和调用指标。",
                        schema(Map.of("agent", prop("string", "智能体的 ID、编码或名称")), "agent"),
                        this::getAgentDetail),
                new AssistantTool("list_knowledge_bases", "查询知识库列表",
                        "查询当前用户可见的知识库，含引擎类型、文档数与 FAQ 数。",
                        schema(Map.of(
                                "keyword", prop("string", "名称、描述或创建人关键词，可选"),
                                "engine", enumProp("引擎：SPRING_AI 平台内置引擎，DIFY 外部引擎，可选", "SPRING_AI", "DIFY"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        this::listKnowledgeBases),
                new AssistantTool("get_gateway_status", "查询模型网关状态",
                        "查询大模型通道是否可用。超级管理员可看到各通道、默认/降级设置与最近探测结果，其他角色只能看到是否可用。",
                        schema(Map.of()),
                        this::getGatewayStatus),
                new AssistantTool("get_usage_summary", "统计用量与成本",
                        "统计一段时间内的调用量、token 用量与估算成本。超级管理员为全站数据，其他角色为本人数据。",
                        schema(Map.of("range", enumProp("时间范围：today 今天，7days 近 7 天（默认），30days 近 30 天",
                                "today", "7days", "30days"))),
                        this::getUsageSummary),
                new AssistantTool("diagnose_agent", "诊断智能体",
                        "排查智能体\"没有回复\"\"回答不对\"\"检索不到\"等问题：检查运行状态、模型通道、知识库绑定与索引、近期调用成功率，给出结论和建议。",
                        schema(Map.of("agent", prop("string", "智能体的 ID、编码或名称")), "agent"),
                        this::diagnoseAgent)
        );
    }

    // ==================== list_agents ====================

    Map<String, Object> listAgents(JsonNode args, CurrentActor actor) {
        int limit = limit(args);
        AgentStatus status = parseStatus(text(args, "status"));
        String scope = "mine".equalsIgnoreCase(text(args, "scope")) ? "mine" : null;
        boolean byCalls = "calls".equalsIgnoreCase(text(args, "sort"));
        // 按调用排序需要先取全量再排序；否则直接取第一页（已按最近更新倒序）
        PageResult<Agent> page = agentService.searchAgents(text(args, "keyword"), text(args, "category"), status,
                scope, actor, 1, byCalls ? 1000 : limit);
        List<Agent> records = new ArrayList<>(page.getRecords());
        if (byCalls) {
            records.sort(Comparator.comparingLong((Agent a) -> a.getCallCount() == null ? 0 : a.getCallCount()).reversed());
        }
        List<Map<String, Object>> items = records.stream().limit(limit).map(this::agentSummary).toList();
        return ok(Map.of("total", page.getTotal(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> agentSummary(Agent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("code", a.getCode());
        m.put("category", a.getCategory());
        m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
        m.put("statusLabel", a.getStatus() != null ? a.getStatus().getLabel() : null);
        m.put("model", blankToNull(a.getModelName()));
        m.put("totalCalls", a.getCallCount() == null ? 0 : a.getCallCount());
        m.put("avgLatencyMs", a.getAvgResponseTimeMs());
        m.put("owner", Boolean.TRUE.equals(a.getIsSystem()) ? "系统内置" : a.getOwnerUsername());
        m.put("updatedAt", format(a.getUpdatedAt()));
        return m;
    }

    // ==================== get_agent_detail ====================

    Map<String, Object> getAgentDetail(JsonNode args, CurrentActor actor) {
        AgentMatch match = resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent a = match.agent();
        Map<String, Object> m = agentSummary(a);
        m.put("description", truncate(a.getDescription(), 200));
        m.put("systemPromptSummary", truncate(a.getSystemPrompt(), PROMPT_SUMMARY_CHARS));
        m.put("systemPromptLength", a.getSystemPrompt() == null ? 0 : a.getSystemPrompt().length());
        m.put("temperature", a.getTemperature());
        m.put("maxTokens", a.getMaxTokens());
        m.put("tags", a.getTags());
        m.put("knowledgeBases", knowledgeBaseRefs(a, actor));
        m.put("enabledTools", enabledToolNames(a.getToolsConfig()));
        m.put("canEdit", authorizationService.canManageAgent(actor, a));
        m.put("canRun", authorizationService.canRunAgent(actor, a));
        return ok(m);
    }

    private List<Map<String, Object>> knowledgeBaseRefs(Agent agent, CurrentActor actor) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (agent.getKnowledgeBaseIds() == null) {
            return refs;
        }
        for (String kbId : agent.getKnowledgeBaseIds()) {
            if (kbId == null || kbId.isBlank()) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("id", kbId);
            Optional<KnowledgeBase> kb = knowledgeBaseRepository.findById(kbId);
            if (kb.isEmpty()) {
                ref.put("exists", false);
            } else if (authorizationService.canViewKnowledgeBase(actor, kb.get())) {
                ref.put("name", kb.get().getName());
                ref.put("engine", kb.get().getProvider());
                ref.put("enabled", !Boolean.FALSE.equals(kb.get().getEnabled()));
            } else {
                // 不泄露无权查看的知识库名称
                ref.put("name", "（无权查看）");
            }
            refs.add(ref);
        }
        return refs;
    }

    List<String> enabledToolNames(String toolsConfig) {
        if (toolsConfig == null || toolsConfig.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(toolsConfig);
            List<String> names = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode tool : root) {
                    if (tool.path("enabled").asBoolean(true)) {
                        String name = tool.path("title").asText(tool.path("name").asText(""));
                        if (!name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== list_knowledge_bases ====================

    Map<String, Object> listKnowledgeBases(JsonNode args, CurrentActor actor) {
        int limit = limit(args);
        String engine = text(args, "engine");
        if (engine != null && !engine.equalsIgnoreCase("SPRING_AI") && !engine.equalsIgnoreCase("DIFY")) {
            engine = null;
        }
        PageResult<KnowledgeBase> page = knowledgeBaseService.searchKnowledgeBases(text(args, "keyword"), engine,
                1, limit, actor);
        List<Map<String, Object>> items = page.getRecords().stream().map(kb -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", kb.getId());
            m.put("name", kb.getName());
            m.put("engine", kb.getProvider());
            m.put("engineLabel", engineLabel(kb.getProvider()));
            m.put("documents", kb.getDocumentCount() == null ? 0 : kb.getDocumentCount());
            m.put("faqs", kb.getFaqCount() == null ? 0 : kb.getFaqCount());
            m.put("enabled", !Boolean.FALSE.equals(kb.getEnabled()));
            m.put("owner", Boolean.TRUE.equals(kb.getIsSystem()) ? "系统内置" : kb.getOwnerUsername());
            m.put("description", truncate(kb.getDescription(), 80));
            m.put("updatedAt", format(kb.getUpdatedAt()));
            return m;
        }).toList();
        return ok(Map.of("total", page.getTotal(), "returned", items.size(), "items", items));
    }

    // ==================== get_gateway_status ====================

    Map<String, Object> getGatewayStatus(JsonNode args, CurrentActor actor) {
        Map<String, String> route = gatewayService.activeRoute();
        boolean available = route.get("channel") != null && !route.get("channel").isBlank();
        if (!actor.isSuperAdmin()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", available);
            m.put("defaultModel", available ? route.get("model") : null);
            m.put("note", "通道明细仅超级管理员可见；如需调整请联系超级管理员。");
            return ok(m);
        }
        GatewayOverview overview = gatewayService.overview();
        String defaultId = overview.getPolicy() != null ? overview.getPolicy().getDefaultProviderId() : null;
        String fallbackId = overview.getPolicy() != null ? overview.getPolicy().getFallbackProviderId() : null;
        List<Map<String, Object>> channels = overview.getProviders().stream().map(p -> channelSummary(p, defaultId, fallbackId)).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available);
        m.put("activeChannel", route.get("channel"));
        m.put("activeModel", route.get("model"));
        m.put("failoverEnabled", overview.getPolicy() != null && Boolean.TRUE.equals(overview.getPolicy().getFailoverEnabled()));
        m.put("readyCount", overview.getReadyCount());
        m.put("channels", channels);
        m.put("embedding", embeddingSummary());
        m.put("difyEngine", difyReadiness());
        return ok(m);
    }

    private Map<String, Object> channelSummary(LlmProviderView p, String defaultId, String fallbackId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("vendor", p.getVendor() != null ? p.getVendor().name() : null);
        m.put("enabled", p.isEnabled());
        m.put("keyConfigured", p.isConfigured());
        m.put("defaultModel", p.getDefaultModel());
        m.put("role", p.getId() != null && p.getId().equals(defaultId) ? "默认通道"
                : p.getId() != null && p.getId().equals(fallbackId) ? "降级通道" : null);
        m.put("lastProbeStatus", p.getLastProbeStatus());
        m.put("lastProbeAt", format(p.getLastProbeAt()));
        m.put("lastProbeMessage", truncate(p.getLastProbeMessage(), 120));
        return m;
    }

    private Map<String, Object> embeddingSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        var active = embeddingConfigService.getActiveConfig();
        m.put("configured", active.isPresent());
        active.ifPresent(e -> {
            m.put("name", e.getName());
            m.put("model", e.getModelName());
            m.put("dimension", e.getDimension());
            m.put("lastProbeStatus", e.getLastProbeStatus());
        });
        return m;
    }

    private Map<String, Object> difyReadiness() {
        var info = difyConfigService.applyReadiness(knowledgeBaseService.getEngineInfo());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", info.isConfigured());
        m.put("ready", info.isReady());
        m.put("probeStatus", info.getProbeStatus());
        return m;
    }

    // ==================== get_usage_summary ====================

    Map<String, Object> getUsageSummary(JsonNode args, CurrentActor actor) {
        String range = text(args, "range");
        if (!"today".equalsIgnoreCase(range) && !"30days".equalsIgnoreCase(range)) {
            range = "7days";
        }
        DashboardStats stats = agentService.getDashboardStats(range.toLowerCase(Locale.ROOT), actor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", actor.isSuperAdmin() ? "全站" : "本人创建的智能体与本人的助手对话");
        m.put("range", switch (range.toLowerCase(Locale.ROOT)) {
            case "today" -> "今天";
            case "30days" -> "近 30 天";
            default -> "近 7 天";
        });
        long totalTokens = stats.getPromptTokens() + stats.getCompletionTokens();
        long assistantTokens = stats.getAssistantPromptTokens() + stats.getAssistantCompletionTokens();
        m.put("totalTokens", totalTokens);
        m.put("promptTokens", stats.getPromptTokens());
        m.put("completionTokens", stats.getCompletionTokens());
        m.put("agentTokens", totalTokens - assistantTokens);
        m.put("assistantTokens", assistantTokens);
        m.put("estimatedCostCny", stats.getEstimatedCostCny());
        m.put("tokenChangePercentVsPreviousPeriod", stats.getTokenChangePercent());
        m.put("tokensByModel", stats.getModelDistribution());
        m.put("agentCallsInPeriod", stats.getPeriodCalls());
        m.put("agentSuccessRatePercentInPeriod", stats.getPeriodCalls() == 0 ? null : stats.getSuccessRate());
        m.put("topAgentsAllTime", stats.getRanking().stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", r.getName());
            item.put("callsAllTime", r.getCalls());
            item.put("tokensInPeriod", r.getTokens());
            return item;
        }).toList());
        m.put("notes", List.of(
                "totalTokens = agentTokens（智能体被调用消耗）+ assistantTokens（平台 AI 助手自身对话消耗），均为本时间段内",
                "agentCallsInPeriod 为本时间段内智能体的调用次数；为 0 时成功率不适用",
                "topAgentsAllTime 按累计调用次数（不限时间）排序，callsAllTime 与本时间段无关；tokensInPeriod 为该智能体本时间段内的 token"));
        return ok(m);
    }

    // ==================== diagnose_agent ====================

    Map<String, Object> diagnoseAgent(JsonNode args, CurrentActor actor) {
        AgentMatch match = resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = match.agent();
        List<Map<String, Object>> checks = new ArrayList<>();

        // 1. 运行状态与权限
        if (agent.getStatus() == AgentStatus.DISABLED) {
            checks.add(check("运行状态", "FAIL", "智能体已停用", "在智能体列表中将其启用后再调试"));
        } else {
            checks.add(check("运行状态", "PASS", agent.getStatus() != null ? agent.getStatus().getLabel() : "运行中", null));
        }
        if (!authorizationService.canRunAgent(actor, agent)) {
            checks.add(check("调试权限", "WARN", "当前账号只能查看，不能运行或调试该智能体",
                    "联系智能体创建人授予运行权限，或使用开发者账号"));
        }

        // 2. 模型通道
        checks.add(checkChannel(agent, actor));

        // 3. 知识库绑定与索引
        checks.addAll(checkKnowledgeBases(agent, actor));

        // 4. 近 7 天调用情况
        checks.add(checkRecentCalls(agent));

        long fails = checks.stream().filter(c -> "FAIL".equals(c.get("status"))).count();
        long warns = checks.stream().filter(c -> "WARN".equals(c.get("status"))).count();
        String conclusion = fails > 0 ? "发现 " + fails + " 个会导致无法回复的问题"
                : warns > 0 ? "可以运行，但有 " + warns + " 项需要注意"
                : "配置检查未发现问题";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", Map.of("id", agent.getId(), "name", agent.getName()));
        m.put("conclusion", conclusion);
        m.put("checks", checks);
        return ok(m);
    }

    private Map<String, Object> checkChannel(Agent agent, CurrentActor actor) {
        var routeOpt = gatewayService.resolveRoute(agent.getModelName());
        if (routeOpt.isEmpty()) {
            return check("模型通道", "FAIL", "没有可用的大模型通道（未启用或未配置 API Key）",
                    actor.isSuperAdmin() ? "到「模型网关 → 大语言模型」启用一个通道并填写 API Key"
                            : "联系超级管理员在「模型网关」中配置可用通道");
        }
        LlmProvider primary = routeOpt.get().primary();
        String effectiveModel = effectiveModel(primary, agent.getModelName());
        String requested = blankToNull(agent.getModelName());
        String modelNote = requested != null && !requested.equalsIgnoreCase(effectiveModel)
                ? "；智能体配置的模型 " + requested + " 不在该通道中，实际使用 " + effectiveModel
                : "";
        if (!actor.isSuperAdmin()) {
            return check("模型通道", "PASS", "有可用通道，实际使用模型 " + effectiveModel + modelNote, null);
        }
        boolean probeFailed = "FAILED".equalsIgnoreCase(primary.getLastProbeStatus())
                || "FAIL".equalsIgnoreCase(primary.getLastProbeStatus());
        String detail = "路由到通道「" + primary.getName() + "」，模型 " + effectiveModel + modelNote
                + (routeOpt.get().fallback() != null ? "；降级通道「" + routeOpt.get().fallback().getName() + "」" : "；未配置降级通道");
        if (probeFailed) {
            return check("模型通道", "WARN", detail + "；最近一次连通性探测失败："
                            + truncate(primary.getLastProbeMessage(), 100),
                    "到「模型网关」对该通道重新测试连接，检查 API Key 与额度");
        }
        return check("模型通道", "PASS", detail, null);
    }

    /** 与 AiChatService 选模型的规则一致：配置的模型在通道模型列表中则使用它，否则用通道默认模型。 */
    static String effectiveModel(LlmProvider provider, String requested) {
        if (requested != null && !requested.isBlank() && provider.getModels() != null
                && Arrays.stream(provider.getModels().split("[,，]")).map(String::trim)
                .anyMatch(item -> item.equalsIgnoreCase(requested.trim()))) {
            return requested.trim();
        }
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            return provider.getDefaultModel();
        }
        return requested;
    }

    private List<Map<String, Object>> checkKnowledgeBases(Agent agent, CurrentActor actor) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> kbIds = agent.getKnowledgeBaseIds() == null ? List.of()
                : agent.getKnowledgeBaseIds().stream().filter(id -> id != null && !id.isBlank()).toList();
        if (kbIds.isEmpty()) {
            checks.add(check("知识库", "PASS", "未绑定知识库（如需基于企业资料回答，可在编排页绑定）", null));
            return checks;
        }
        // 与对话时的依赖校验完全一致：失败时对话会被直接拒绝
        try {
            authorizationService.checkAgentKnowledgeBaseDependencies(agent);
        } catch (IllegalStateException e) {
            checks.add(check("知识库依赖", "FAIL", e.getMessage(), "重新绑定可用的知识库，或请知识库创建人授予使用权限"));
        }
        for (String kbId : kbIds) {
            Optional<KnowledgeBase> kbOpt = knowledgeBaseRepository.findById(kbId);
            if (kbOpt.isEmpty() || !authorizationService.canViewKnowledgeBase(actor, kbOpt.get())) {
                continue; // 不存在的已由依赖校验报告；无权查看的不展示细节
            }
            KnowledgeBase kb = kbOpt.get();
            String item = "知识库「" + kb.getName() + "」";
            int docs = kb.getDocumentCount() == null ? 0 : kb.getDocumentCount();
            int faqs = kb.getFaqCount() == null ? 0 : kb.getFaqCount();
            if (docs + faqs == 0) {
                checks.add(check(item, "WARN", "知识库为空，检索不到任何内容", "上传文档或添加 FAQ"));
                continue;
            }
            if ("DIFY".equalsIgnoreCase(kb.getProvider())) {
                var dify = difyReadiness();
                if (!Boolean.TRUE.equals(dify.get("ready"))) {
                    checks.add(check(item, "FAIL", "Dify 外部引擎未就绪（" + dify.get("probeStatus") + "）",
                            "到「模型网关 → Dify 知识引擎」检查地址与 API Key 并测试连接"));
                } else {
                    checks.add(check(item, "PASS", "Dify 外部引擎，" + docs + " 篇文档、" + faqs + " 条 FAQ", null));
                }
            } else {
                checks.add(checkSpringAiKnowledgeBase(item, kb, docs, faqs));
            }
        }
        return checks;
    }

    private Map<String, Object> checkSpringAiKnowledgeBase(String item, KnowledgeBase kb, int docs, int faqs) {
        KnowledgeIndexVersion version = kb.getActiveIndexVersionId() == null ? null
                : indexVersionRepository.findById(kb.getActiveIndexVersionId()).orElse(null);
        if (version != null && !"READY".equalsIgnoreCase(version.getStatus())) {
            return check(item, "WARN", "当前索引版本状态为 " + version.getStatus() + "，检索结果可能不完整",
                    "在知识库详情中查看索引任务，失败时重新构建");
        }
        var embedding = embeddingConfigService.getActiveConfig();
        if (embedding.isEmpty()) {
            return check(item, "WARN", "未激活向量模型，平台内置引擎只能做字符级近似匹配，语义检索效果差",
                    "到「模型网关 → 向量模型」添加并激活一个向量模型，然后重建索引");
        }
        if ("FAILED".equalsIgnoreCase(embedding.get().getLastProbeStatus())) {
            return check(item, "WARN", "生效的向量模型最近一次连通性测试失败，新文档可能无法向量化",
                    "到「模型网关 → 向量模型」重新测试连接");
        }
        return check(item, "PASS", "平台内置引擎，" + docs + " 篇文档、" + faqs + " 条 FAQ", null);
    }

    private Map<String, Object> checkRecentCalls(Agent agent) {
        LocalDate end = LocalDate.now();
        List<AgentDailyStat> rows = dailyStatRepository.findByAgentIdAndStatDateBetween(agent.getId(), end.minusDays(6), end);
        long calls = rows.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        long success = rows.stream().mapToLong(AgentDailyStat::getSuccessCount).sum();
        if (calls == 0) {
            return check("近 7 天调用", "PASS", "近 7 天没有调用记录", null);
        }
        double rate = Math.round(success * 1000.0 / calls) / 10.0;
        if (rate < 80) {
            return check("近 7 天调用", "WARN", calls + " 次调用，成功率 " + rate + "%（失败多为模型通道报错或降级）",
                    "在智能体的「日志」中查看失败的对话");
        }
        return check("近 7 天调用", "PASS", calls + " 次调用，成功率 " + rate + "%", null);
    }

    private static Map<String, Object> check(String item, String status, String detail, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("status", status);
        m.put("detail", detail);
        if (suggestion != null) {
            m.put("suggestion", suggestion);
        }
        return m;
    }

    // ==================== 智能体解析 ====================

    record AgentMatch(Agent agent, Map<String, Object> response) {
    }

    /**
     * 按 ID → 编码/名称精确匹配 → 关键词模糊匹配 解析智能体，只在当前用户可见范围内查找。
     * 多个候选时不猜测，把候选返回给模型让用户确认。
     */
    AgentMatch resolveAgent(String ref, CurrentActor actor) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("请提供智能体的名称、编码或 ID");
        }
        String key = ref.trim();
        try {
            Optional<Agent> byId = agentService.getById(key, actor);
            if (byId.isPresent()) {
                return new AgentMatch(byId.get(), null);
            }
        } catch (IllegalStateException e) {
            // 无权访问：与"不存在"同样处理，不泄露资源是否存在
        }
        List<Agent> candidates = agentService.searchAgents(key, null, null, null, actor, 1, 50).getRecords();
        List<Agent> exact = candidates.stream()
                .filter(a -> key.equalsIgnoreCase(a.getName()) || key.equalsIgnoreCase(a.getCode()))
                .toList();
        if (exact.size() == 1) {
            return new AgentMatch(exact.get(0), null);
        }
        List<Agent> pool = exact.isEmpty() ? candidates : exact;
        if (pool.size() == 1) {
            return new AgentMatch(pool.get(0), null);
        }
        if (pool.isEmpty()) {
            return new AgentMatch(null, Map.of("ok", false,
                    "error", "没有找到名称、编码或 ID 为「" + key + "」的智能体（或当前账号无权查看）"));
        }
        List<Map<String, Object>> options = pool.stream().limit(MAX_LIMIT)
                .map(a -> Map.<String, Object>of("id", a.getId(), "name", a.getName())).toList();
        return new AgentMatch(null, Map.of("ok", false, "ambiguous", true,
                "error", "匹配到多个智能体，请让用户确认是哪一个", "candidates", options));
    }

    // ==================== 工具方法 ====================

    private static Map<String, Object> ok(Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(body);
        return m;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (required.length > 0) {
            m.put("required", List.of(required));
        }
        return m;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static int limit(JsonNode args) {
        JsonNode node = args == null ? null : args.get("limit");
        int value = node != null && node.canConvertToInt() ? node.asInt() : DEFAULT_LIMIT;
        if (node != null && node.isTextual()) {
            try {
                value = Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                value = DEFAULT_LIMIT;
            }
        }
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }

    private static AgentStatus parseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return AgentStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String engineLabel(String provider) {
        return "SPRING_AI".equalsIgnoreCase(provider) ? "平台内置引擎" : "Dify 外部引擎";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "…" : compact;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME);
    }
}
