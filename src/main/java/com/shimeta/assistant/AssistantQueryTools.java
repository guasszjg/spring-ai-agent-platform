package com.shimeta.assistant;

import com.shimeta.mcp.McpServerService;
import com.shimeta.mcp.McpServerView;
import com.shimeta.mcp.McpToolView;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentDailyStat;
import com.shimeta.model.AgentRouteChoice;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AgentTemplate;
import com.shimeta.service.AgentTemplateService;
import com.shimeta.model.DashboardStats;
import com.shimeta.model.GatewayOverview;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.model.KnowledgeIndexVersion;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.LlmProviderView;
import com.shimeta.model.PageResult;
import com.shimeta.repository.AgentDailyStatRepository;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeIndexVersionRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentService;
import com.shimeta.service.DifyConfigService;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.model.PlatformToolView;
import com.shimeta.skill.SkillService;
import com.shimeta.skill.SkillView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(AssistantQueryTools.class);

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
    private final AgentTemplateService templateService;
    private final PlatformDocsService platformDocsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpServerService mcpServerService;
    private SkillService skillService;
    private PlatformToolService platformToolService;
    private AssistantExtendedQueryTools extendedQueryTools;
    private ObjectProvider<AssistantExtendedQueryTools> extendedQueryToolsProvider;

    public AssistantQueryTools(AgentService agentService,
                               KnowledgeBaseService knowledgeBaseService,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               KnowledgeIndexVersionRepository indexVersionRepository,
                               LlmGatewayService gatewayService,
                               EmbeddingConfigService embeddingConfigService,
                               DifyConfigService difyConfigService,
                               ResourceAuthorizationService authorizationService,
                               AgentDailyStatRepository dailyStatRepository,
                               AgentTemplateService templateService,
                               PlatformDocsService platformDocsService) {
        this.templateService = templateService;
        this.platformDocsService = platformDocsService;
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

    @Autowired(required = false)
    public void setMcpServerService(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @Autowired(required = false)
    public void setSkillService(SkillService skillService) {
        this.skillService = skillService;
    }

    @Autowired(required = false)
    public void setPlatformToolService(PlatformToolService platformToolService) {
        this.platformToolService = platformToolService;
    }

    @Autowired(required = false)
    public void setExtendedQueryToolsProvider(ObjectProvider<AssistantExtendedQueryTools> provider) {
        this.extendedQueryToolsProvider = provider;
    }

    /** 供单元测试手动注入扩展只读工具；Spring 运行期走 {@link #setExtendedQueryToolsProvider} 避免构造循环依赖。 */
    public void setExtendedQueryTools(AssistantExtendedQueryTools extendedQueryTools) {
        this.extendedQueryTools = extendedQueryTools;
    }

    private AssistantExtendedQueryTools resolveExtendedQueryTools() {
        if (extendedQueryTools != null) {
            return extendedQueryTools;
        }
        return extendedQueryToolsProvider != null ? extendedQueryToolsProvider.getIfAvailable() : null;
    }

    public List<AssistantTool> tools() {
        return List.of(
                AssistantTool.read("list_agents", "查询智能体列表",
                        "查询当前用户可见的智能体列表。用户问\"有哪些智能体\"\"哪些在运行\"\"哪个调用最多\"时使用。",
                        schema(Map.of(
                                "keyword", prop("string", "名称、编码、描述、标签或创建人关键词，可选"),
                                "status", enumProp("运行状态，可选", "RUNNING", "IDLE", "DISABLED"),
                                "category", prop("string", "业务分类，可选"),
                                "scope", enumProp("范围：mine 只看我创建的，all 看我可见的全部（默认）", "mine", "all"),
                                "sort", enumProp("排序：updated 最近更新（默认），calls 累计调用次数", "updated", "calls"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        (args, ctx) -> listAgents(args, ctx.actor())),
                AssistantTool.read("get_agent_detail", "查看智能体配置",
                        "查看一个智能体的完整配置摘要：模型与通道路由、提示词摘要、上下文记忆、思考模式、知识库使用方式、绑定的知识库/技能/工具、状态和调用指标。toolStates 包含工具开关；toolsUsingPlatformDefaults 为 true 时使用平台默认工具。toolStateError 表示状态未知。",
                        schema(Map.of("agent", prop("string", "智能体的 ID、编码或名称")), "agent"),
                        (args, ctx) -> getAgentDetail(args, ctx.actor())),
                AssistantTool.read("list_knowledge_bases", "查询知识库列表",
                        "查询当前用户可见的知识库，含引擎类型、文档数与 FAQ 数。",
                        schema(Map.of(
                                "keyword", prop("string", "名称、描述或创建人关键词，可选"),
                                "engine", enumProp("引擎：SPRING_AI 平台内置引擎，DIFY 外部引擎，可选", "SPRING_AI", "DIFY"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        (args, ctx) -> listKnowledgeBases(args, ctx.actor())),
                AssistantTool.read("list_knowledge_documents", "查询知识库文档",
                        "查询指定知识库中的文档列表、索引状态（已完成/处理中/失败）及失败原因。用户问\"知识库里有哪些文档\"\"哪篇文档索引失败了\"时使用。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "keyword", prop("string", "文档名称关键词，可选"),
                                "status", enumProp("索引状态过滤，可选：completed 已完成，error 失败，indexing 处理中，waiting 排队中",
                                        "completed", "error", "indexing", "waiting"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20")), "knowledgeBase"),
                        (args, ctx) -> listKnowledgeDocuments(args, ctx.actor())),
                AssistantTool.read("list_mcp_servers", "查询 MCP 服务与工具",
                        "查询平台接入的 MCP 服务及已审核启用的 MCP 工具（不含服务地址与密钥）。用户问\"有哪些 MCP 服务\"\"有哪些 MCP 工具\"时使用。",
                        schema(Map.of(
                                "keyword", prop("string", "服务名称或工具名称关键词，可选"))),
                        (args, ctx) -> listMcpServers(args, ctx.actor())),
                AssistantTool.read("list_skills", "查询技能库",
                        "查询当前用户可见的技能（Skill）列表，含触发场景、所需工具、版本、启用状态与使用统计。用户问\"有哪些技能\"\"技能库里有什么\"时使用。",
                        schema(Map.of(
                                "keyword", prop("string", "技能名称或描述关键词，可选"),
                                "scope", enumProp("范围：all 全部可见（默认），mine 我创建的，system 系统内置", "all", "mine", "system"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        (args, ctx) -> listSkills(args, ctx.actor())),
                AssistantTool.read("get_gateway_status", "查询模型网关状态",
                        "查询大模型通道是否可用。超级管理员可看到各通道、默认/降级设置与最近探测结果，其他角色只能看到是否可用。",
                        schema(Map.of()),
                        (args, ctx) -> getGatewayStatus(args, ctx.actor())),
                AssistantTool.read("get_usage_summary", "统计用量、模型分布与成本",
                        "统计一段时间内的调用量、token 用量、估算成本，以及各模型的使用分布排行（含按 Token 消耗和按调用次数，对应概览页模型分布图）和热门智能体排行。用户问\"用了多少 token\"\"成本多少\"\"使用最多的模型是哪个\"\"哪个模型调用最多\"时必须使用此工具。超级管理员为全站数据，其他角色为本人数据。",
                        schema(Map.of("range", enumProp("时间范围：today 今天，7days 近 7 天（默认），30days 近 30 天",
                                "today", "7days", "30days"))),
                        (args, ctx) -> getUsageSummary(args, ctx.actor())),
                AssistantTool.read("diagnose_agent", "诊断智能体",
                        "排查智能体\"没有回复\"\"回答不对\"\"检索不到\"\"技能或自主检索没生效\"等问题：检查运行状态、模型通道与工具调用能力、知识库绑定与索引、近期调用成功率，给出结论和建议。",
                        schema(Map.of("agent", prop("string", "智能体的 ID、编码或名称")), "agent"),
                        (args, ctx) -> diagnoseAgent(args, ctx.actor())),
                AssistantTool.read("list_templates", "查询场景模板",
                        "按行业或关键词查询可套用的智能体场景模板。用户想基于模板创建智能体、或问\"有哪些模板\"时使用。",
                        schema(Map.of(
                                "keyword", prop("string", "模板名称、描述关键词，可选"),
                                "category", prop("string", "分类，可选"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20"))),
                        (args, ctx) -> listTemplates(args, ctx.actor())),
                AssistantTool.read("search_platform_docs", "检索平台文档",
                        "检索平台使用文档。回答\"怎么用\"\"在哪里设置\"\"是什么意思\"\"报错怎么办\"等平台使用问题前先调用，以文档为准回答。",
                        schema(Map.of("query", prop("string", "检索问题，用完整的一句话描述")), "query"),
                        (args, ctx) -> searchPlatformDocs(args, ctx.actor())),
                AssistantTool.adminRead("test_gateway_channel", "测试模型通道",
                        "对一个大模型通道发起一次连通性测试并返回结果（仅超级管理员）。用户问\"XX 通道还能用吗\"\"帮我测一下通道\"时使用。",
                        schema(Map.of("channel", prop("string", "通道名称或 ID")), "channel"),
                        (args, ctx) -> testGatewayChannel(args, ctx.actor())),
                AssistantTool.read("navigate_to_page", "跳转平台页面",
                        "替用户直接打开或跳转到指定页面（支持跨路由无缝切换）。当用户说\"进入这个智能体的调试界面\"\"打开 XX 智能体\"\"去知识库看看\"\"切到模型网关\"\"返回智能体列表\"时必须调用此工具。",
                        schema(Map.of(
                                "page", enumProp("目标页面：agent_debug（智能体调试页）、agents（智能体列表）、knowledge（知识库列表或详情）、overview（概览）、templates（场景模板）、skills（技能库）、tools（工具管理）、gateway（模型网关，仅超管）、users（用户管理，仅超管）、security（开放与安全）",
                                        "agent_debug", "agents", "knowledge", "overview", "templates", "skills", "tools", "gateway", "users", "security"),
                                "agent", prop("string", "当 page=agent_debug 时必填：智能体的 ID、编码或名称"),
                                "knowledgeBase", prop("string", "当 page=knowledge 且要直接打开某知识库详情时填写：知识库 ID 或名称"),
                                "subTab", prop("string", "可选子页签：agent_debug 支持 orchestrate/logs/monitor/api；knowledge 支持 documents/faqs/retrieval-test/evaluation；tools 支持 tools/mcp；security 支持 overview/keys/clients/policy/audit/alerts"),
                                "keyword", prop("string", "可选：跳转后顺便在目标页搜索框输入的关键词")),
                                "page"),
                        (args, ctx) -> navigateToPage(args, ctx.actor()))
        );
    }

    // ==================== navigate_to_page ====================

    Map<String, Object> navigateToPage(JsonNode args, CurrentActor actor) {
        String page = text(args, "page");
        if (page == null) {
            throw new IllegalArgumentException("请指定目标页面 page");
        }
        String normalizedPage = page.toLowerCase(Locale.ROOT);
        String subTab = text(args, "subTab");
        String keyword = text(args, "keyword");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("navigated", true);
        m.put("page", normalizedPage);
        if (subTab != null) {
            m.put("subTab", subTab);
        }
        if (keyword != null) {
            m.put("keyword", keyword);
        }

        switch (normalizedPage) {
            case "agent_debug" -> {
                String agentRef = text(args, "agent");
                if (agentRef == null) {
                    throw new IllegalArgumentException("打开智能体调试页时请提供智能体的名称、编码或 ID");
                }
                AgentMatch match = resolveAgent(agentRef, actor);
                if (match.agent() == null) {
                    return match.response();
                }
                Agent agent = match.agent();
                String url = agentLink(agent.getId());
                if (subTab != null && !subTab.isBlank() && !"orchestrate".equalsIgnoreCase(subTab)) {
                    url = url + "?tab=" + ("api".equalsIgnoreCase(subTab) ? "access" : subTab.toLowerCase(Locale.ROOT));
                }
                m.put("agentId", agent.getId());
                m.put("agentName", agent.getName());
                m.put("title", "智能体「" + agent.getName() + "」调试页");
                m.put("url", url);
                m.put("link", url);
                return ok(m);
            }
            case "knowledge" -> {
                String kbRef = text(args, "knowledgeBase");
                if (kbRef != null) {
                    KnowledgeBase kb = resolveKnowledgeBase(kbRef, actor);
                    String tab = "DIFY".equalsIgnoreCase(kb.getProvider()) ? "knowledge-dify" : "knowledge";
                    String url = "/dashboard?tab=" + tab + "&kb=" + kb.getId();
                    m.put("kbId", kb.getId());
                    m.put("kbName", kb.getName());
                    m.put("dashboardTab", tab);
                    m.put("title", "知识库「" + kb.getName() + "」");
                    m.put("url", url);
                    m.put("link", url);
                    return ok(m);
                }
                String url = "/dashboard?tab=knowledge";
                m.put("dashboardTab", "knowledge");
                m.put("title", "自建知识库");
                m.put("url", url);
                m.put("link", url);
                return ok(m);
            }
            case "gateway", "users" -> {
                if (!actor.isSuperAdmin()) {
                    throw new IllegalStateException("该页面仅超级管理员可访问");
                }
                String url = "/dashboard?tab=" + normalizedPage;
                m.put("dashboardTab", normalizedPage);
                m.put("title", "gateway".equals(normalizedPage) ? "模型网关" : "用户管理");
                m.put("url", url);
                m.put("link", url);
                return ok(m);
            }
            case "overview", "agents", "templates", "skills", "tools", "security" -> {
                String url = "/dashboard?tab=" + normalizedPage;
                m.put("dashboardTab", normalizedPage);
                m.put("title", PAGE_NAMES.getOrDefault(normalizedPage, normalizedPage));
                m.put("url", url);
                m.put("link", url);
                return ok(m);
            }
            default -> throw new IllegalArgumentException("不支持的目标页面：" + page);
        }
    }

    // ==================== test_gateway_channel ====================

    Map<String, Object> testGatewayChannel(JsonNode args, CurrentActor actor) {
        if (!actor.isSuperAdmin()) {
            throw new IllegalStateException("测试模型通道仅超级管理员可用");
        }
        String ref = text(args, "channel");
        if (ref == null) {
            throw new IllegalArgumentException("请提供通道名称或 ID");
        }
        List<LlmProviderView> providers = gatewayService.overview().getProviders();
        List<LlmProviderView> matched = providers.stream()
                .filter(p -> ref.equalsIgnoreCase(p.getId()) || ref.equalsIgnoreCase(p.getName()))
                .toList();
        if (matched.isEmpty()) {
            matched = providers.stream().filter(p -> p.getName() != null
                    && p.getName().toLowerCase(Locale.ROOT).contains(ref.toLowerCase(Locale.ROOT))).toList();
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("没有找到通道「" + ref + "」，可用通道："
                    + String.join("、", providers.stream().map(LlmProviderView::getName).toList()));
        }
        if (matched.size() > 1) {
            throw new IllegalArgumentException("匹配到多个通道，请让用户确认：" + String.join("、", matched.stream().map(LlmProviderView::getName).toList()));
        }
        LlmProviderView probed = gatewayService.probe(matched.get(0).getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", probed.getName());
        m.put("status", probed.getLastProbeStatus());
        m.put("message", truncate(probed.getLastProbeMessage(), 200));
        m.put("testedAt", format(probed.getLastProbeAt()));
        m.put("enabled", probed.isEnabled());
        m.put("keyConfigured", probed.isConfigured());
        return ok(m);
    }

    // ==================== 页面上下文与链接 ====================

    static String agentLink(String agentId) {
        return "/debug/" + agentId;
    }

    static String knowledgeBaseLink(String kbId) {
        return "/dashboard?tab=knowledge&kb=" + kbId;
    }

    private static final Map<String, String> PAGE_NAMES = Map.ofEntries(
            Map.entry("overview", "概览"), Map.entry("agents", "智能体列表"), Map.entry("templates", "场景模板"),
            Map.entry("tools", "工具"), Map.entry("skills", "技能库"),
            Map.entry("knowledge", "自建知识库"), Map.entry("knowledge-dify", "Dify 知识库"),
            Map.entry("gateway", "模型网关"), Map.entry("gateway-llm", "模型网关 · 大语言模型"),
            Map.entry("gateway-embedding", "模型网关 · 向量模型"), Map.entry("gateway-rerank", "模型网关 · Rerank 模型"),
            Map.entry("gateway-ocr", "模型网关 · OCR 识别"), Map.entry("gateway-dify", "模型网关 · Dify 知识引擎"),
            Map.entry("users", "用户"), Map.entry("roles", "角色与权限"), Map.entry("security", "开放与安全"),
            Map.entry("assistant-eval", "助手评测"), Map.entry("debug", "智能体编排与调试"));

    /**
     * 把前端传来的页面上下文转成给模型的说明。资源只在当前用户有权查看时才展开名称，
     * 伪造或无权的资源 ID 一律忽略，不泄露任何信息。
     */
    String describeContext(Map<String, String> context, CurrentActor actor) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        String page = PAGE_NAMES.get(context.getOrDefault("page", ""));
        String type = context.get("resourceType");
        String id = context.get("resourceId");
        String resource = null;
        if (id != null && !id.isBlank()) {
            if ("AGENT".equalsIgnoreCase(type)) {
                try {
                    resource = agentService.getById(id, actor)
                            .map(a -> "智能体「" + a.getName() + "」（ID " + a.getId() + "）")
                            .orElse(null);
                } catch (IllegalStateException ignored) {
                    resource = null;
                }
            } else if ("KNOWLEDGE_BASE".equalsIgnoreCase(type)) {
                resource = knowledgeBaseRepository.findById(id)
                        .filter(kb -> authorizationService.canViewKnowledgeBase(actor, kb))
                        .map(kb -> "知识库「" + kb.getName() + "」（ID " + kb.getId() + "）")
                        .orElse(null);
            }
        }
        if (page == null && resource == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (page != null) {
            sb.append("用户当前在「").append(page).append("」页面");
        }
        if (resource != null) {
            sb.append(sb.isEmpty() ? "用户" : "，").append("正在查看").append(resource)
                    .append("。用户说\"这个\"\"当前的\"智能体或知识库时，指的就是它，直接用它的 ID 调用工具");
        }
        return sb.append("。").toString();
    }

    // ==================== list_templates ====================

    Map<String, Object> listTemplates(JsonNode args, CurrentActor actor) {
        int limit = limit(args);
        List<AgentTemplate> templates = templateService.list(text(args, "keyword"), text(args, "category"), actor);
        List<Map<String, Object>> items = templates.stream().limit(limit).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("category", t.getCategory());
            m.put("description", truncate(t.getDescription(), 80));
            m.put("builtin", Boolean.TRUE.equals(t.getIsBuiltin()));
            return m;
        }).toList();
        return ok(Map.of("total", templates.size(), "returned", items.size(), "items", items));
    }

    // ==================== search_platform_docs ====================

    Map<String, Object> searchPlatformDocs(JsonNode args) {
        return searchPlatformDocs(args, null);
    }

    Map<String, Object> searchPlatformDocs(JsonNode args, CurrentActor actor) {
        String query = text(args, "query");
        if (query == null) {
            throw new IllegalArgumentException("请提供检索问题");
        }
        if (!platformDocsService.isReady()) {
            platformDocsService.search(query, 1, actor); // 触发后台导入
            return Map.of("ok", false, "error", "平台文档尚未就绪（未激活向量模型或正在导入），请根据你已知的平台功能介绍回答，并说明可能不够准确");
        }
        List<Map<String, Object>> snippets = platformDocsService.search(query, 4, actor).stream().map(chunk -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", chunk.sourceName());
            m.put("content", truncate(chunk.content(), 1200));
            return m;
        }).toList();
        if (snippets.isEmpty()) {
            return Map.of("ok", true, "items", List.of(), "note", "平台文档中没有找到相关内容，如实告诉用户不确定，不要编造");
        }
        return ok(Map.of("items", snippets));
    }

    /**
     * 按 ID → 名称精确 → 关键词模糊 解析当前用户可见的知识库；多个候选时抛出业务异常，把候选名称交给模型让用户确认。
     */
    KnowledgeBase resolveKnowledgeBase(String ref, CurrentActor actor) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("请提供知识库的名称或 ID");
        }
        String key = ref.trim();
        Optional<KnowledgeBase> byId = knowledgeBaseRepository.findById(key);
        if (byId.isPresent() && authorizationService.canViewKnowledgeBase(actor, byId.get())) {
            return byId.get();
        }
        List<KnowledgeBase> candidates = knowledgeBaseService.searchKnowledgeBases(key, null, 1, 50, actor).getRecords();
        List<KnowledgeBase> exact = candidates.stream().filter(kb -> key.equalsIgnoreCase(kb.getName())).toList();
        List<KnowledgeBase> pool = exact.isEmpty() ? candidates : exact;
        if (pool.size() == 1) {
            return pool.get(0);
        }
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("没有找到名称或 ID 为「" + key + "」的知识库（或当前账号无权查看）");
        }
        throw new IllegalArgumentException("匹配到多个知识库，请让用户确认是哪一个："
                + String.join("、", pool.stream().limit(MAX_LIMIT).map(KnowledgeBase::getName).toList()));
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
        m.put("routeMode", AgentRouteChoice.of(a).isCustom() ? "自选通道" : "跟随平台默认");
        String kbMode = Agent.normalizeKnowledgeMode(a.getKnowledgeMode());
        m.put("knowledgeMode", kbMode);
        m.put("knowledgeModeLabel", Agent.KNOWLEDGE_AGENTIC.equals(kbMode) ? "模型自主检索" : "先检索后回答");
        m.put("skillCount", a.getSkillIds() == null ? 0 : (int) a.getSkillIds().stream().filter(s -> s != null && !s.isBlank()).count());
        m.put("totalCalls", a.getCallCount() == null ? 0 : a.getCallCount());
        m.put("avgLatencyMs", a.getAvgResponseTimeMs());
        m.put("owner", Boolean.TRUE.equals(a.getIsSystem()) ? "系统内置" : a.getOwnerUsername());
        m.put("updatedAt", format(a.getUpdatedAt()));
        m.put("link", agentLink(a.getId()));
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
        m.put("memoryTurns", a.resolveMemoryTurns());
        m.put("memoryCompression", a.isMemoryCompressionEnabled());
        m.put("thinkingMode", Agent.normalizeThinkingMode(a.getThinkingMode()));
        if (a.getPromptVariables() != null && !a.getPromptVariables().isEmpty()) {
            m.put("promptVariables", a.getPromptVariables().stream().map(v -> Map.of(
                    "key", v.getKey(),
                    "label", v.getLabel() != null ? v.getLabel() : v.getKey(),
                    "type", v.getType() != null ? v.getType() : "text",
                    "required", Boolean.TRUE.equals(v.getRequired()),
                    "defaultValue", v.getDefaultValue() != null ? v.getDefaultValue() : ""
            )).toList());
        }
        m.put("route", agentRouteSummary(a, actor));
        m.put("tags", a.getTags());
        m.put("knowledgeBases", knowledgeBaseRefs(a, actor));
        m.put("skills", skillRefs(a, actor));
        try {
            List<JsonNode> configured = configuredTools(a.getToolsConfig());
            List<JsonNode> effective = configured.isEmpty() ? defaultToolEntries(agentToolCatalog()) : configured;
            m.put("enabledTools", enabledToolLabels(effective));
            m.put("enabledToolDetails", effective.stream().filter(AssistantQueryTools::toolEnabled)
                    .map(AssistantQueryTools::toolIdentity).toList());
            m.put("toolStates", effective.stream().map(tool -> {
                Map<String, Object> state = toolIdentity(tool);
                state.put("enabled", toolEnabled(tool));
                return state;
            }).toList());
            m.put("toolsUsingPlatformDefaults", configured.isEmpty());
        } catch (RuntimeException e) {
            log.warn("Failed to resolve agent tool settings: {}", e.getMessage());
            m.put("toolStateError", "读取工具状态失败，无法确认哪些工具已启用");
        }
        boolean canEdit = authorizationService.canManageAgent(actor, a);
        m.put("canEdit", canEdit);
        m.put("canRun", authorizationService.canRunAgent(actor, a));
        if (canEdit) {
            List<Map<String, Object>> choices = selectableModelChoices();
            if (!choices.isEmpty()) {
                m.put("availableModelChoices", choices);
            }
        }
        return ok(m);
    }

    /** 供可编辑智能体的角色（含开发者）在切换模型时查看可选通道与模型名（与调试页下拉列表一致，不含地址/密钥）。 */
    private List<Map<String, Object>> selectableModelChoices() {
        try {
            var options = gatewayService.chatRouteOptions();
            if (options == null || options.channels() == null) {
                return List.of();
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (var ch : options.channels()) {
                if (!ch.selectable()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("channel", ch.name());
                row.put("defaultModel", ch.defaultModel());
                row.put("models", ch.models());
                row.put("supportsTools", ch.supportsTools());
                list.add(row);
            }
            return list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Map<String, Object> agentRouteSummary(Agent agent, CurrentActor actor) {
        Map<String, Object> r = new LinkedHashMap<>();
        AgentRouteChoice choice = AgentRouteChoice.of(agent);
        r.put("followPlatform", !choice.isCustom());
        Optional<LlmGatewayService.ResolvedRoute> routeOpt;
        String notice = null;
        if (choice.isCustom()) {
            var agentRoute = gatewayService.resolveAgentRoute(choice);
            routeOpt = agentRoute.map(LlmGatewayService.AgentRoute::route);
            notice = agentRoute.map(LlmGatewayService.AgentRoute::notice).orElse(null);
        } else {
            routeOpt = gatewayService.resolveRoute(agent.getModelName());
        }
        r.put("available", routeOpt.isPresent());
        if (routeOpt.isPresent()) {
            LlmProvider primary = routeOpt.get().primary();
            r.put("effectiveModel", effectiveModel(primary, agent.getModelName()));
            r.put("supportsTools", LlmGatewayService.supportsTools(primary));
            if (actor != null && actor.isSuperAdmin()) {
                r.put("primaryChannel", primary.getName());
                if (routeOpt.get().fallback() != null) {
                    r.put("fallbackChannel", routeOpt.get().fallback().getName());
                }
            }
        }
        if (notice != null) {
            r.put("notice", notice);
        }
        return r;
    }

    private List<Map<String, Object>> skillRefs(Agent agent, CurrentActor actor) {
        List<String> ids = agent.getSkillIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, SkillView> visibleById = new LinkedHashMap<>();
        if (skillService != null && actor != null) {
            try {
                for (SkillView s : skillService.list(actor)) {
                    visibleById.put(s.id(), s);
                }
            } catch (RuntimeException ignored) {
                // 技能列表不可用时退回仅展示 ID
            }
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String skillId : ids) {
            if (skillId == null || skillId.isBlank()) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("id", skillId);
            SkillView view = visibleById.get(skillId);
            if (view != null) {
                ref.put("name", view.name());
                ref.put("enabled", view.enabled());
                ref.put("isSystem", view.isSystem());
            } else if (skillService != null) {
                ref.put("name", "（无权查看或已删除）");
            }
            refs.add(ref);
        }
        return refs;
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
        List<JsonNode> configured;
        try {
            configured = configuredTools(toolsConfig);
        } catch (IllegalStateException e) {
            return List.of();
        }
        return enabledToolLabels(configured.isEmpty() ? defaultToolEntries(agentToolCatalog()) : configured);
    }

    static List<String> enabledToolLabels(List<JsonNode> tools) {
        return tools.stream().filter(AssistantQueryTools::toolEnabled)
                .map(tool -> firstNonBlankText(tool.path("title"), tool.path("name"), tool.path("code")))
                .filter(name -> !name.isBlank()).toList();
    }

    static boolean toolEnabled(JsonNode tool) {
        return tool.path("enabled").asBoolean(true);
    }

    List<JsonNode> configuredTools(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        try {
            JsonNode json = objectMapper.readTree(config);
            List<JsonNode> entries = new ArrayList<>();
            if (json != null && json.isArray()) {
                json.forEach(tool -> {
                    if (tool.isObject()) entries.add(tool);
                });
            }
            return entries;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("智能体工具配置无法读取", e);
        }
    }

    List<JsonNode> defaultToolEntries(List<PlatformToolView> catalog) {
        return catalog.stream().filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .filter(t -> !"MCP".equalsIgnoreCase(t.getCategory()))
                .map(this::toolEntry).toList();
    }

    JsonNode toolEntry(PlatformToolView view) {
        var entry = objectMapper.createObjectNode();
        entry.put("id", view.getId());
        entry.put("code", view.getCode());
        entry.put("name", view.getName());
        entry.put("title", view.getTitle());
        entry.put("prefix", view.getPrefix());
        entry.put("category", view.getCategory());
        entry.put("enabled", true);
        return entry;
    }

    List<PlatformToolView> agentToolCatalog() {
        if (platformToolService == null) {
            return List.of();
        }
        return platformToolService.listAgentToolOptions();
    }

    private static Map<String, Object> toolIdentity(JsonNode tool) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (String field : List.of("code", "name", "title", "prefix")) {
            String value = tool.path(field).asText("").trim();
            if (!value.isEmpty()) item.put(field, value);
        }
        return item;
    }

    private static String firstNonBlankText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && !n.isNull() && !n.isMissingNode()) {
                String v = n.asText("").trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return "";
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
            m.put("link", knowledgeBaseLink(kb.getId()));
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

    // ==================== list_knowledge_documents ====================

    Map<String, Object> listKnowledgeDocuments(JsonNode args, CurrentActor actor) {
        KnowledgeBase kb = resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        String keyword = text(args, "keyword");
        String status = text(args, "status");
        int limit = limit(args);

        PageResult<KnowledgeDocument> page = knowledgeBaseService.searchDocuments(kb.getId(), keyword, status, 1, limit, actor);
        List<KnowledgeDocument> records = page != null && page.getRecords() != null ? page.getRecords() : List.of();

        // 统计整个知识库各索引状态数量，方便直接回答“有没有索引失败的文档”
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        PageResult<KnowledgeDocument> allPage = (keyword == null && status == null && page != null && page.getTotal() <= limit)
                ? page
                : knowledgeBaseService.searchDocuments(kb.getId(), null, null, 1, 500, actor);
        if (allPage != null && allPage.getRecords() != null) {
            for (KnowledgeDocument doc : allPage.getRecords()) {
                String st = doc.getIndexingStatus() != null && !doc.getIndexingStatus().isBlank()
                        ? doc.getIndexingStatus().toLowerCase(Locale.ROOT) : "unknown";
                statusCounts.merge(st, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> items = records.stream().map(doc -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("name", doc.getName());
            item.put("format", doc.getFileFormat() != null ? doc.getFileFormat() : doc.getExtension());
            item.put("indexingStatus", doc.getIndexingStatus());
            item.put("statusLabel", documentStatusLabel(doc.getIndexingStatus()));
            item.put("wordCount", doc.getWordCount() == null ? 0L : doc.getWordCount());
            item.put("tokenCount", doc.getTokenCount() == null ? 0L : doc.getTokenCount());
            item.put("enabled", !Boolean.FALSE.equals(doc.getEnabled()));
            if (doc.getErrorMessage() != null && !doc.getErrorMessage().isBlank()) {
                item.put("errorMessage", truncate(doc.getErrorMessage(), 150));
            }
            item.put("updatedAt", format(doc.getUpdatedAt()));
            return item;
        }).toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "engine", engineLabel(kb.getProvider()),
                "link", knowledgeBaseLink(kb.getId())));
        res.put("total", page != null ? page.getTotal() : items.size());
        res.put("returned", items.size());
        res.put("statusSummary", statusCounts);
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "当前筛选条件下该知识库没有匹配的文档（total=0），请如实告知用户，严禁编造文档。");
        }
        return ok(res);
    }

    private static String documentStatusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed" -> "已完成";
            case "error", "failed" -> "索引失败";
            case "indexing" -> "处理中";
            case "waiting" -> "排队中";
            default -> status;
        };
    }

    // ==================== list_mcp_servers ====================

    Map<String, Object> listMcpServers(JsonNode args, CurrentActor actor) {
        if (mcpServerService == null) {
            return Map.of("ok", false, "error", "MCP 服务模块未启用");
        }
        String kw = text(args, "keyword");
        String lowerKw = kw == null ? null : kw.toLowerCase(Locale.ROOT);

        if (actor != null && actor.isSuperAdmin()) {
            List<McpServerView> all = mcpServerService.list();
            List<Map<String, Object>> servers = new ArrayList<>();
            int totalEnabledTools = 0;
            for (McpServerView s : all) {
                List<McpToolView> tools = s.getTools() == null ? List.of() : s.getTools();
                boolean serverMatches = lowerKw == null
                        || containsIgnoreCase(s.getName(), lowerKw)
                        || containsIgnoreCase(s.getSlug(), lowerKw)
                        || containsIgnoreCase(s.getRemark(), lowerKw);
                List<McpToolView> matchedTools = serverMatches ? tools : tools.stream()
                        .filter(t -> containsIgnoreCase(t.getToolName(), lowerKw)
                                || containsIgnoreCase(t.getTitle(), lowerKw)
                                || containsIgnoreCase(t.getFunctionName(), lowerKw)
                                || containsIgnoreCase(t.getDescription(), lowerKw))
                        .toList();
                if (!serverMatches && matchedTools.isEmpty()) {
                    continue;
                }
                totalEnabledTools += s.getEnabledToolCount();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", s.getId());
                item.put("name", s.getName());
                item.put("slug", s.getSlug());
                item.put("transport", s.getTransport());
                item.put("enabled", s.isEnabled());
                item.put("lastStatus", s.getLastStatus());
                if (s.getLastMessage() != null && !s.getLastMessage().isBlank()) {
                    item.put("lastMessage", truncate(s.getLastMessage(), 120));
                }
                item.put("lastCheckedAt", format(s.getLastCheckedAt()));
                item.put("toolCount", s.getToolCount());
                item.put("enabledToolCount", s.getEnabledToolCount());
                item.put("pendingToolCount", s.getPendingToolCount());
                item.put("tools", matchedTools.stream().limit(MAX_LIMIT).map(t -> {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("functionName", t.getFunctionName());
                    tm.put("title", t.getTitle() != null ? t.getTitle() : t.getToolName());
                    tm.put("category", t.getCategory());
                    tm.put("enabled", t.isEnabled());
                    tm.put("reviewStatus", t.getReviewStatus());
                    tm.put("writeOperation", t.isWriteOperation());
                    return tm;
                }).toList());
                servers.add(item);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("scope", "超级管理员视图（含全部 MCP 服务状态与工具审核情况，不含地址与密钥）");
            res.put("serverCount", servers.size());
            res.put("enabledToolCount", totalEnabledTools);
            res.put("servers", servers);
            if (servers.isEmpty()) {
                res.put("note", "当前平台还没有接入任何 MCP 服务（serverCount=0），请如实告知用户可到「构建 → 工具 → MCP 服务」添加，严禁编造。");
            }
            return ok(res);
        }

        // 非超级管理员：只看已审核启用的 MCP 工具，按服务名称分组，不暴露服务配置与地址
        List<McpToolView> available = mcpServerService.availableTools();
        Map<String, List<Map<String, Object>>> byServer = new LinkedHashMap<>();
        int count = 0;
        for (McpToolView t : available) {
            if (lowerKw != null
                    && !containsIgnoreCase(t.getServerName(), lowerKw)
                    && !containsIgnoreCase(t.getToolName(), lowerKw)
                    && !containsIgnoreCase(t.getTitle(), lowerKw)
                    && !containsIgnoreCase(t.getDescription(), lowerKw)) {
                continue;
            }
            count++;
            String serverName = t.getServerName() != null ? t.getServerName() : "MCP 服务";
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("functionName", t.getFunctionName());
            tm.put("title", t.getTitle() != null ? t.getTitle() : t.getToolName());
            tm.put("category", t.getCategory());
            tm.put("writeOperation", t.isWriteOperation());
            tm.put("description", truncate(t.getDescription(), 100));
            byServer.computeIfAbsent(serverName, k -> new ArrayList<>()).add(tm);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        byServer.forEach((serverName, toolList) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("serverName", serverName);
            g.put("toolCount", toolList.size());
            g.put("tools", toolList.stream().limit(MAX_LIMIT).toList());
            groups.add(g);
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("scope", "可用 MCP 工具视图（仅含已审核启用的工具）");
        res.put("availableToolCount", count);
        res.put("servers", groups);
        if (count == 0) {
            res.put("note", "当前没有已审核启用的 MCP 工具（availableToolCount=0），请如实告知用户，严禁编造。");
        }
        return ok(res);
    }

    // ==================== list_skills ====================

    Map<String, Object> listSkills(JsonNode args, CurrentActor actor) {
        if (skillService == null) {
            return Map.of("ok", false, "error", "技能库模块未启用");
        }
        String kw = text(args, "keyword");
        String lowerKw = kw == null ? null : kw.toLowerCase(Locale.ROOT);
        String scope = text(args, "scope");
        int limit = limit(args);

        List<SkillView> visible = skillService.list(actor);
        List<SkillView> filtered = visible.stream()
                .filter(s -> {
                    if ("system".equalsIgnoreCase(scope)) {
                        return s.isSystem();
                    }
                    if ("mine".equalsIgnoreCase(scope)) {
                        return !s.isSystem() && actor != null && actor.getUserId() != null
                                && actor.getUserId().equals(s.ownerId());
                    }
                    return true;
                })
                .filter(s -> lowerKw == null
                        || containsIgnoreCase(s.name(), lowerKw)
                        || containsIgnoreCase(s.description(), lowerKw))
                .toList();

        List<Map<String, Object>> items = filtered.stream().limit(limit).map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.id());
            item.put("name", s.name());
            item.put("description", truncate(s.description(), 120));
            item.put("requiredTools", s.requiredTools());
            item.put("enabled", s.enabled());
            item.put("isSystem", s.isSystem());
            item.put("owner", s.isSystem() ? "系统内置" : s.ownerUsername());
            item.put("version", s.version());
            item.put("loadCount", s.loadCount());
            item.put("agentCount", s.agentCount());
            item.put("updatedAt", format(s.updatedAt()));
            return item;
        }).toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", filtered.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (filtered.isEmpty()) {
            res.put("note", "当前技能库中共有 0 个可见技能（为空），请如实告知用户目前还没有任何技能，可在「构建 → 技能库」新建或导入 SKILL.md，严禁编造不存在的技能。");
        }
        return ok(res);
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

        Map<String, Long> tokensByModel = sortUsageDesc(stats.getModelDistribution());
        Map<String, Long> callsByModel = sortUsageDesc(stats.getModelCallDistribution());
        long modelTokenTotal = tokensByModel.values().stream().mapToLong(Long::longValue).sum();

        Set<String> allModels = new LinkedHashSet<>();
        allModels.addAll(tokensByModel.keySet());
        allModels.addAll(callsByModel.keySet());

        List<Map<String, Object>> modelRanking = new ArrayList<>();
        List<String> orderedModels = allModels.stream()
                .sorted(Comparator.<String>comparingLong(name -> tokensByModel.getOrDefault(name, 0L)).reversed()
                        .thenComparing(Comparator.<String>comparingLong(name -> callsByModel.getOrDefault(name, 0L)).reversed())
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int i = 0; i < orderedModels.size(); i++) {
            String model = orderedModels.get(i);
            long modelTokens = tokensByModel.getOrDefault(model, 0L);
            long modelCalls = callsByModel.getOrDefault(model, 0L);
            double share = modelTokenTotal > 0 ? Math.round(modelTokens * 1000.0 / modelTokenTotal) / 10.0 : 0.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("model", model);
            row.put("tokens", modelTokens);
            row.put("tokenSharePercent", share);
            row.put("calls", modelCalls);
            modelRanking.add(row);
        }

        Map<String, Object> topByTokens = null;
        if (!tokensByModel.isEmpty()) {
            var first = tokensByModel.entrySet().iterator().next();
            double share = modelTokenTotal > 0 ? Math.round(first.getValue() * 1000.0 / modelTokenTotal) / 10.0 : 0.0;
            topByTokens = new LinkedHashMap<>();
            topByTokens.put("model", first.getKey());
            topByTokens.put("tokens", first.getValue());
            topByTokens.put("tokenSharePercent", share);
        }
        Map<String, Object> topByCalls = null;
        if (!callsByModel.isEmpty()) {
            var first = callsByModel.entrySet().iterator().next();
            topByCalls = new LinkedHashMap<>();
            topByCalls.put("model", first.getKey());
            topByCalls.put("calls", first.getValue());
        }

        m.put("topModelByTokens", topByTokens);
        m.put("topModelByCalls", topByCalls);
        m.put("modelUsageRanking", modelRanking);
        m.put("tokensByModel", tokensByModel);
        m.put("callsByModel", callsByModel);
        m.put("agentCallsInPeriod", stats.getPeriodCalls());
        m.put("agentSuccessRatePercentInPeriod", stats.getPeriodCalls() == 0 ? null : stats.getSuccessRate());
        m.put("agentFailoversInPeriod", stats.getFailoverCount());
        m.put("deletedAgentTokensInPeriod", stats.getDeletedAgentTokens());
        m.put("topAgentsInPeriod", stats.getRanking().stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", r.getName());
            item.put("model", r.getModel());
            item.put("callsInPeriod", r.getCalls());
            item.put("tokensInPeriod", r.getTokens());
            return item;
        }).toList());
        m.put("notes", List.of(
                "totalTokens = agentTokens（智能体被调用消耗）+ assistantTokens（平台 AI 助手自身对话消耗），均为本时间段内",
                "modelUsageRanking 与 tokensByModel 对应概览页的「模型分布」图（按 Token 用量从高到低排序，含智能体与 AI 助手）；callsByModel 为按模型回复次数统计",
                "回答\"使用最多的模型\"时，请直接给出 topModelByTokens（按 Token 消耗）与 topModelByCalls（按调用次数）的具体模型名与数值；两者均为空说明该时间段内暂无对话记录",
                "agentCallsInPeriod 为本时间段内智能体的调用次数；为 0 时成功率不适用",
                "agentFailoversInPeriod 为主通道失败、切到备用通道的调用次数，这些调用可能仍算成功",
                "超级管理员的 totalTokens 含已删除智能体的用量（deletedAgentTokensInPeriod）",
                "topAgentsInPeriod 只含现存智能体，按本时间段内调用次数排序"));
        return ok(m);
    }

    private static Map<String, Long> sortUsageDesc(Map<String, Long> source) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return sorted;
        }
        source.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
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

        // 2. 模型通道与工具调用兼容性
        checks.add(checkChannel(agent, actor));
        Map<String, Object> toolCapCheck = checkToolCapabilities(agent, actor);
        if (toolCapCheck != null) {
            checks.add(toolCapCheck);
        }

        // 3. 知识库绑定与索引
        checks.addAll(checkKnowledgeBases(agent, actor));

        // 4. 近 7 天调用情况、近期对话日志与工具失败率
        checks.add(checkRecentCalls(agent));
        AssistantExtendedQueryTools ext = resolveExtendedQueryTools();
        if (ext != null) {
            checks.addAll(ext.diagnoseLogsAndTools(agent, actor));
        }

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
        com.shimeta.model.AgentRouteChoice choice = com.shimeta.model.AgentRouteChoice.of(agent);
        String routeNotice = null;
        java.util.Optional<LlmGatewayService.ResolvedRoute> routeOpt;
        if (choice.isCustom()) {
            var agentRoute = gatewayService.resolveAgentRoute(choice);
            routeOpt = agentRoute.map(LlmGatewayService.AgentRoute::route);
            routeNotice = agentRoute.map(LlmGatewayService.AgentRoute::notice).orElse(null);
        } else {
            routeOpt = gatewayService.resolveRoute(agent.getModelName());
        }
        if (routeOpt.isEmpty()) {
            return check("模型通道", "FAIL", "没有可用的大模型通道（未启用或未配置 API Key）",
                    actor.isSuperAdmin() ? "到「模型网关 → 大语言模型」启用一个通道并填写 API Key"
                            : "联系超级管理员在「模型网关」中配置可用通道");
        }
        if (routeNotice != null) {
            return check("模型通道", "WARN", routeNotice,
                    "到智能体调试页重新选择模型通道并发布，或选「跟随平台默认」");
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

    /** 技能、模型自主检索、外部工具都依赖通道支持工具调用；不支持时主动提醒 */
    private Map<String, Object> checkToolCapabilities(Agent agent, CurrentActor actor) {
        boolean agenticKb = Agent.KNOWLEDGE_AGENTIC.equals(Agent.normalizeKnowledgeMode(agent.getKnowledgeMode()))
                && agent.getKnowledgeBaseIds() != null && !agent.getKnowledgeBaseIds().isEmpty();
        boolean hasSkills = agent.getSkillIds() != null && agent.getSkillIds().stream().anyMatch(s -> s != null && !s.isBlank());
        boolean hasTools = !enabledToolNames(agent.getToolsConfig()).isEmpty();
        if (!agenticKb && !hasSkills && !hasTools) {
            return null;
        }
        AgentRouteChoice choice = AgentRouteChoice.of(agent);
        Optional<LlmGatewayService.ResolvedRoute> routeOpt = choice.isCustom()
                ? gatewayService.resolveAgentRoute(choice).map(LlmGatewayService.AgentRoute::route)
                : gatewayService.resolveRoute(agent.getModelName());
        if (routeOpt.isEmpty()) {
            return null;
        }
        LlmProvider primary = routeOpt.get().primary();
        if (LlmGatewayService.supportsTools(primary)) {
            return null;
        }
        List<String> affected = new ArrayList<>();
        if (agenticKb) {
            affected.add("知识库「模型自主检索」（会自动退回先检索后回答）");
        }
        if (hasSkills) {
            affected.add("绑定的技能（无法按需加载）");
        }
        if (hasTools) {
            affected.add("已启用的工具（对话时不会下发）");
        }
        String suggestion = actor != null && actor.isSuperAdmin()
                ? "在调试页为该智能体切换支持工具调用的通道，或在「模型网关 → 大语言模型」开启该通道的「支持工具调用」"
                : "在调试页为该智能体切换支持工具调用的通道，或联系超级管理员调整通道能力设置";
        return check("工具调用能力", "WARN", "当前生效通道不支持工具调用，以下配置不会生效：" + String.join("、", affected), suggestion);
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
