package com.shimeta.assistant;

import com.shimeta.model.EmbeddingConfig;
import com.shimeta.rag.pipeline.LocalEmbeddingService;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.EmbeddingConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台 AI 助手的语义工具路由器（4.5 选对工具）：
 * <ol>
 *   <li>把全部读写工具按业务域分成 10 个语义工具组，每组附典型问法（含口语与同义表达）；</li>
 *   <li>若平台已激活向量模型，缓存各组问法向量并按余弦相似度选出最相关的 2–3 组（向量服务异常时自动冷却并平滑退回关键词规则）；</li>
 *   <li>支持多轮对话组继承（追问“它/这个/刚才/继续”时自动保留上一轮工具组）与页面上下文联动；</li>
 *   <li>同时收窄只读工具与写工具集合，让模型每轮只看 3–7 个最相关工具，并标明是否命中查询数据组以决定是否启用 {@code tool_choice=required} 兜底。</li>
 * </ol>
 */
@Component
public class AssistantToolRouter {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolRouter.class);

    static final double MIN_VECTOR_SCORE = 0.36;
    static final double SCORE_MARGIN = 0.10;
    static final int MAX_SELECTED_GROUPS = 3;
    static final int MAX_FOCUSED_READ_TOOLS = 9;
    private static final long EMBEDDING_COOLDOWN_MS = 60_000L;

    private static final Pattern FOLLOWUP_CUE = Pattern.compile(
            "(这个|那个|它|刚才|上面|前面|继续|再看|详细|具体|顺便|还有|改成|调成|帮我改|帮我查|那它|它的|里面的)");
    private static final Pattern PURE_CHITCHAT = Pattern.compile(
            "^(你好|您好|嗨|hi|hello|hey|在吗|在不在|在么|有人吗|在呢|测试|测试一下|test|ping|早|早安|早上好|中午好|下午好|晚上好|晚安|"
                    + "谢谢|多谢|感谢|再见|拜拜|好的|好|ok|okay|嗯|嗯嗯|收到|明白|明白了|哈哈|哈哈哈|辛苦了|没事|不用了|"
                    + "你是谁|你能做什么|介绍一下你自己|你有什么功能)[！!。.?？~～\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOWTO_CUE = Pattern.compile(
            "(怎么|如何|在哪里|在哪|什么意思|啥意思|区别|对比|原理|步骤|教程|怎么配|如何配置|如何接入|文档)");
    private static final Pattern INSTANCE_DATA_CUE = Pattern.compile(
            "(有哪些|有多少|几个|哪几个|列表|清单|查一下|查看|看看|帮我查|帮我测|测试一下|诊断|排查|统计|排行|最多|最费钱|花了|花费|用了多少|"
                    + "我的|当前|现在|目前|最近|今日|今天|近7天|近30天|日志|报错|失败|索引失败|没回复|没有回复|回答不对|通不通|能不能用|召回|配置|详情|状态|账单)");

    static boolean isPureChitchat(String message) {
        return message != null && PURE_CHITCHAT.matcher(message.trim()).matches();
    }

    public record ToolGroup(
            String id,
            String label,
            Set<String> readTools,
            Set<String> writeTools,
            boolean readDataGroup,
            boolean superAdminOnly,
            List<String> examples,
            Pattern keywordPattern
    ) {
    }

    public record ToolSelection(
            Set<String> groupIds,
            Set<String> allowedReadTools,
            Set<String> allowedWriteTools,
            boolean hitsReadDataGroup,
            String strategy
    ) {
        public static final ToolSelection ALL = new ToolSelection(Set.of(), null, null, false, "all");
    }

    static final List<ToolGroup> GROUPS = List.of(
            new ToolGroup("agents", "智能体与诊断",
                    Set.of("list_agents", "get_agent_detail", "diagnose_agent", "get_agent_logs", "list_resources", "get_resource"),
                    Set.of("create_agent", "set_agent_status", "update_agent_prompt", "bind_knowledge_base",
                            "update_agent_settings", "rollback_agent_prompt", "save_agent_as_template", "delete_resource"),
                    true, false,
                    List.of(
                            "目前有哪些智能体和机器人",
                            "哪些助手正在运行或停用了",
                            "查看客服助手的详细配置和系统提示词",
                            "这个智能体为什么没有回复或回答不准确",
                            "查一下智能体最近的对话日志和报错记录",
                            "查看或回滚智能体的提示词历史版本",
                            "修改智能体模型温度记忆轮数思考模式知识库模式关联技能工具",
                            "帮我新建停用启用或删除某个智能体"
                    ),
                    Pattern.compile("(?i)(智能体|agent|助手|机器人|提示词|prompt|回滚|温度|记忆轮数|思考模式|诊断|排查|没回复|没有回复|回答不对|不准确|检索不到|对话日志|历史版本|停用|启用)")),
            new ToolGroup("knowledge", "知识库与召回",
                    Set.of("list_knowledge_bases", "list_knowledge_documents", "run_retrieval_test", "list_resources", "get_resource"),
                    Set.of("create_knowledge_base", "add_faq", "bind_knowledge_base",
                            "update_kb_settings", "update_faq", "rebuild_kb_index", "run_rag_eval", "delete_resource"),
                    true, false,
                    List.of(
                            "平台里有哪些知识库和资料库",
                            "知识库里有哪些文档哪篇索引失败了",
                            "帮我对知识库做一次召回测试看看能不能搜到",
                            "查一下或修改删除知识库里的 FAQ 常见问答",
                            "修改知识库检索参数 Top K 阈值权重 Rerank 或重建索引跑检索评测",
                            "新建或删除一个知识库或文档"
                    ),
                    Pattern.compile("(?i)(知识库|资料库|kb|文档|切片|索引|重建索引|faq|问答对|召回|检索测试|检索评测|rag|dify|topk|阈值|权重)")),
            new ToolGroup("templates", "场景模板",
                    Set.of("list_templates", "list_resources", "get_resource"),
                    Set.of("create_agent", "save_agent_as_template", "delete_resource"),
                    true, false,
                    List.of(
                            "有哪些现成的行业场景模板",
                            "把智能体存为场景模板或新建删除模板",
                            "查看内置和自定义智能体模板"
                    ),
                    Pattern.compile("(?i)(模板|template|套用|存为模板)")),
            new ToolGroup("tools_mcp", "工具与 MCP",
                    Set.of("list_mcp_servers", "get_tool_usage", "list_resources", "get_resource"),
                    Set.of("create_http_tool", "register_mcp_server", "refresh_mcp_server", "update_agent_settings", "delete_resource"),
                    true, false,
                    List.of(
                            "平台接入了哪些 MCP 服务和工具",
                            "新建或删除自定义 HTTP 工具",
                            "登记刷新或删除远程 MCP 服务",
                            "查一下工具调用次数失败率和平均耗时"
                    ),
                    Pattern.compile("(?i)(mcp|工具|插件|http\\s*工具|博查|联网搜索|工具调用|工具统计|工具失败|刷新工具)")),
            new ToolGroup("skills", "技能库",
                    Set.of("list_skills"),
                    Set.of("create_skill", "update_agent_settings", "delete_resource"),
                    true, false,
                    List.of(
                            "技能库里有哪些可用技能",
                            "新建或删除一个 Markdown 技能",
                            "给智能体关联或取消关联技能"
                    ),
                    Pattern.compile("(?i)(技能|skill)")),
            new ToolGroup("gateway", "模型网关",
                    Set.of("get_gateway_status", "test_gateway_channel"),
                    Set.of("manage_gateway_channel"),
                    true, false,
                    List.of(
                            "模型网关状态和默认通道是什么",
                            "帮我测试一下某个模型通道通不通能不能用",
                            "更换大模型通道密钥新增或启停模型通道",
                            "切换激活向量模型 Rerank 模型或更新 Dify 密钥"
                    ),
                    Pattern.compile("(?i)(网关|通道|大模型|向量模型|embedding|rerank|ocr|连通|测试连接|通不通|能不能用|主通道|备用通道|降级)")),
            new ToolGroup("usage", "用量与成本",
                    Set.of("get_usage_summary", "list_agents"),
                    Set.of(),
                    true, false,
                    List.of(
                            "最近用了多少 token 花了多少钱",
                            "使用最多的模型是哪个模型分布排行",
                            "哪个智能体调用次数最多消耗最高",
                            "查一下今天近7天近30天的调用量和成本统计"
                    ),
                    Pattern.compile("(?i)(用量|成本|费用|花钱|花费|花了|token|排行|最多|分布|统计|调用量|调用次数)")),
            new ToolGroup("security", "开放与安全",
                    Set.of("list_resources", "get_resource"),
                    Set.of("create_api_key", "revoke_api_key", "update_guardrail_policy"),
                    true, false,
                    List.of(
                            "有哪些开放 API 凭证和 API Key",
                            "查看接入终端设备列表和审批状态",
                            "查看或修改护栏策略紧急停用敏感词和 PII 脱敏",
                            "查一下最近的安全审计日志和告警规则",
                            "签发停用轮换或吊销开放 API Key 凭证"
                    ),
                    Pattern.compile("(?i)(凭证|api\\s*key|apikey|密钥|终端|x-client-id|护栏|敏感词|pii|注入|紧急停用|kill\\s*switch|审计日志|拦截记录|告警规则|告警|吊销|轮换)")),
            new ToolGroup("users_roles", "用户与角色",
                    Set.of("list_resources", "get_resource"),
                    Set.of("manage_user"),
                    true, true,
                    List.of(
                            "平台里有哪些用户账号和状态",
                            "新建平台用户修改用户角色启停账号或重置密码",
                            "系统有哪些角色各有什么权限矩阵"
                    ),
                    Pattern.compile("(?i)(用户列表|有哪些用户|租户用户|账号列表|新建用户|创建用户|修改.*角色|改.*角色|停用.*用户|启用.*用户|重置.*密码|角色列表|角色与权限|哪些角色)")),
            new ToolGroup("docs", "平台使用文档",
                    Set.of("search_platform_docs"),
                    Set.of(),
                    false, false,
                    List.of(
                            "平台功能怎么用在哪里设置",
                            "报错 401 403 422 429 是什么意思怎么解决",
                            "先检索后回答和模型自主检索有什么区别",
                            "温度 Top P 混合权重 Rerank 参数如何调整",
                            "如何部署启动和配置开放接口调用"
                    ),
                    Pattern.compile("(?i)(怎么|如何|在哪里|在哪|什么意思|区别|配置项|参数|报错|错误码|401|403|422|429|部署|运维|开放接口|使用文档|平台文档)"))
    );

    private static final Map<String, ToolGroup> GROUP_BY_ID;

    static {
        Map<String, ToolGroup> map = new LinkedHashMap<>();
        for (ToolGroup g : GROUPS) {
            map.put(g.id(), g);
        }
        GROUP_BY_ID = Map.copyOf(map);
    }

    private final EmbeddingConfigService embeddingConfigService;
    private final LocalEmbeddingService localEmbeddingService;

    private volatile String cachedConfigKey;
    private volatile Map<String, List<float[]>> cachedGroupVectors;
    private volatile long embeddingFailedUntilMs = 0L;

    public AssistantToolRouter() {
        this(null, null);
    }

    @Autowired
    public AssistantToolRouter(@Autowired(required = false) EmbeddingConfigService embeddingConfigService,
                               @Autowired(required = false) LocalEmbeddingService localEmbeddingService) {
        this.embeddingConfigService = embeddingConfigService;
        this.localEmbeddingService = localEmbeddingService;
    }

    /**
     * 为当前提问选出最相关的工具子集。
     *
     * @param message        当前用户提问
     * @param pageContext    前端传来的页面上下文（page / resourceType / resourceId）
     * @param previousGroups 上一轮选中的工具组（用于多轮追问继承）
     * @param actor          当前用户（用于过滤超管专属工具组）
     * @param includeWrite   本轮是否挂载写工具
     */
    public ToolSelection route(String message,
                               Map<String, String> pageContext,
                               Set<String> previousGroups,
                               CurrentActor actor,
                               boolean includeWrite) {
        if (message == null || message.isBlank()) {
            return ToolSelection.ALL;
        }
        String trimmed = message.trim();
        if (PURE_CHITCHAT.matcher(trimmed).matches()) {
            return new ToolSelection(Set.of(), Set.of("search_platform_docs"), null, false, "chitchat");
        }

        boolean isSuperAdmin = actor != null && actor.isSuperAdmin();
        Set<String> selectedGroups = new LinkedHashSet<>();
        String strategy = "keyword";

        // 1. 优先尝试向量相似度选组（有激活 Embedding 配置且未处于失败冷却期）
        Optional<Set<String>> vectorMatched = routeByVector(trimmed, pageContext, isSuperAdmin);
        if (vectorMatched.isPresent()) {
            selectedGroups.addAll(vectorMatched.get());
            strategy = "vector";
        }

        // 2. 结合关键词规则补充或作为无向量模型时的主路由
        Set<String> keywordMatched = routeByKeywords(trimmed, isSuperAdmin);
        if (vectorMatched.isEmpty()) {
            selectedGroups.addAll(keywordMatched);
        } else if (!keywordMatched.isEmpty()) {
            // 关键词高置信命中但向量未选入时补入（避免专有名词如 MCP/FAQ/401 漏选）
            for (String gId : keywordMatched) {
                if (selectedGroups.size() < MAX_SELECTED_GROUPS) {
                    selectedGroups.add(gId);
                }
            }
            strategy = "hybrid";
        }

        // 3. 页面上下文联动（仅由页面上下文兜底补入时，不视为明确查询平台数据，避免寒暄/闲聊误触发 tool_choice=required）
        boolean matchedByMessage = !selectedGroups.isEmpty();
        String contextGroup = groupFromPageContext(pageContext, isSuperAdmin);
        if (contextGroup != null && (selectedGroups.isEmpty() || looksLikeFollowUp(trimmed))) {
            selectedGroups.add(contextGroup);
        }

        // 4. 多轮追问组继承
        if (previousGroups != null && !previousGroups.isEmpty()
                && (selectedGroups.isEmpty() || looksLikeFollowUp(trimmed))) {
            for (String prev : previousGroups) {
                ToolGroup g = GROUP_BY_ID.get(prev);
                if (g != null && (!g.superAdminOnly() || isSuperAdmin)) {
                    selectedGroups.add(prev);
                }
            }
        }

        // 限制最终组数量（保留前 MAX_SELECTED_GROUPS 个，若已有诊断意图则确保 agents 在内）
        if (selectedGroups.size() > MAX_SELECTED_GROUPS) {
            Set<String> capped = new LinkedHashSet<>();
            for (String gId : selectedGroups) {
                if (capped.size() >= MAX_SELECTED_GROUPS) {
                    break;
                }
                capped.add(gId);
            }
            selectedGroups = capped;
        }

        if (selectedGroups.isEmpty()) {
            return ToolSelection.ALL;
        }

        Set<String> readTools = new LinkedHashSet<>();
        Set<String> writeTools = new LinkedHashSet<>();
        boolean anyReadDataGroup = false;
        for (String gId : selectedGroups) {
            ToolGroup g = GROUP_BY_ID.get(gId);
            if (g == null) {
                continue;
            }
            readTools.addAll(g.readTools());
            if (includeWrite) {
                writeTools.addAll(g.writeTools());
            }
            if (g.readDataGroup()) {
                anyReadDataGroup = true;
            }
        }

        // 诊断场景常跨知识库和模型网关，自动补入关键诊断只读工具
        if (trimmed.matches(".*(诊断|排查|没回复|没有回复|回答不对|不准确|检索不到).*")) {
            readTools.add("diagnose_agent");
            readTools.add("get_agent_detail");
            readTools.add("get_agent_logs");
            readTools.add("get_gateway_status");
            readTools.add("list_knowledge_bases");
            readTools.add("list_knowledge_documents");
        }

        boolean hasInstanceCue = INSTANCE_DATA_CUE.matcher(trimmed).find();
        boolean pureHowTo = HOWTO_CUE.matcher(trimmed).find() && !hasInstanceCue && !includeWrite;
        if (pureHowTo) {
            readTools.add("search_platform_docs");
        }
        // 仅当用户消息本身命中业务域且带有明确数据查询/排查意图时，才启用 tool_choice=required 兜底；
        // 仅靠页面上下文补入或泛聊/寒暄绝不强制调工具
        boolean hitsReadData = anyReadDataGroup && !pureHowTo
                && (hasInstanceCue || (matchedByMessage && (!keywordMatched.isEmpty() || trimmed.length() >= 8)));

        Set<String> effectiveRead = readTools.isEmpty() || readTools.size() > MAX_FOCUSED_READ_TOOLS ? null : readTools;
        Set<String> effectiveWrite = includeWrite && !writeTools.isEmpty() ? writeTools : null;

        return new ToolSelection(Set.copyOf(selectedGroups), effectiveRead, effectiveWrite, hitsReadData, strategy);
    }

    private Optional<Set<String>> routeByVector(String message, Map<String, String> pageContext, boolean isSuperAdmin) {
        if (embeddingConfigService == null || localEmbeddingService == null) {
            return Optional.empty();
        }
        Optional<EmbeddingConfig> activeOpt = embeddingConfigService.getActiveConfig();
        if (activeOpt.isEmpty()) {
            return Optional.empty();
        }
        EmbeddingConfig cfg = activeOpt.get();
        String configKey = configKey(cfg);
        long now = System.currentTimeMillis();
        if (Objects.equals(configKey, cachedConfigKey) && now < embeddingFailedUntilMs) {
            return Optional.empty();
        }

        try {
            Map<String, List<float[]>> groupVectors = ensureGroupVectors(cfg, configKey);
            if (groupVectors == null || groupVectors.isEmpty()) {
                return Optional.empty();
            }
            String queryText = enrichQueryWithContext(message, pageContext);
            float[] queryVec = embedSingle(queryText, cfg);
            if (queryVec == null || queryVec.length == 0) {
                return Optional.empty();
            }

            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (ToolGroup g : GROUPS) {
                if (g.superAdminOnly() && !isSuperAdmin) {
                    continue;
                }
                List<float[]> exVecs = groupVectors.get(g.id());
                if (exVecs == null || exVecs.isEmpty()) {
                    continue;
                }
                double best = -1.0;
                for (float[] v : exVecs) {
                    double sim = cosine(queryVec, v);
                    if (sim > best) {
                        best = sim;
                    }
                }
                if (best >= MIN_VECTOR_SCORE) {
                    scored.add(Map.entry(g.id(), best));
                }
            }
            scored.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());
            if (scored.isEmpty()) {
                return Optional.of(Set.of());
            }
            double topScore = scored.get(0).getValue();
            Set<String> picked = new LinkedHashSet<>();
            for (var entry : scored) {
                if (picked.size() >= MAX_SELECTED_GROUPS) {
                    break;
                }
                if (picked.isEmpty() || topScore - entry.getValue() <= SCORE_MARGIN) {
                    picked.add(entry.getKey());
                }
            }
            return Optional.of(picked);
        } catch (RuntimeException e) {
            log.debug("Assistant vector tool routing unavailable, falling back to keyword routing: {}", e.getMessage());
            cachedConfigKey = configKey;
            embeddingFailedUntilMs = System.currentTimeMillis() + EMBEDDING_COOLDOWN_MS;
            return Optional.empty();
        }
    }

    private Map<String, List<float[]>> ensureGroupVectors(EmbeddingConfig cfg, String configKey) {
        if (Objects.equals(configKey, cachedConfigKey) && cachedGroupVectors != null) {
            return cachedGroupVectors;
        }
        synchronized (this) {
            if (Objects.equals(configKey, cachedConfigKey) && cachedGroupVectors != null) {
                return cachedGroupVectors;
            }
            List<String> allExamples = new ArrayList<>();
            for (ToolGroup g : GROUPS) {
                allExamples.addAll(g.examples());
            }
            List<float[]> vectors = embedBatch(allExamples, cfg);
            if (vectors == null || vectors.size() != allExamples.size()) {
                return null;
            }
            Map<String, List<float[]>> built = new LinkedHashMap<>();
            int idx = 0;
            for (ToolGroup g : GROUPS) {
                List<float[]> groupList = new ArrayList<>(g.examples().size());
                for (int i = 0; i < g.examples().size(); i++) {
                    groupList.add(normalize(vectors.get(idx++)));
                }
                built.put(g.id(), groupList);
            }
            cachedGroupVectors = built;
            cachedConfigKey = configKey;
            embeddingFailedUntilMs = 0L;
            return built;
        }
    }

    private List<float[]> embedBatch(List<String> texts, EmbeddingConfig cfg) {
        // 生产环境优先一次批量请求，减少 RTT
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            try {
                List<float[]> batch = embeddingConfigService.requestEmbeddings(
                        cfg.getBaseUrl(),
                        embeddingConfigService.decryptKey(cfg.getApiKeyEncrypted()),
                        embeddingConfigService.decryptHeaders(cfg),
                        cfg.getModelName(),
                        texts,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(10));
                if (batch != null && batch.size() == texts.size()) {
                    return batch;
                }
            } catch (RuntimeException e) {
                // 如果批量失败且没有可本地执行的 mock，直接上抛触发冷却降级
                throw e;
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            float[] v = localEmbeddingService.embed(t);
            if (v == null || v.length == 0) {
                return null;
            }
            out.add(v);
        }
        return out;
    }

    private float[] embedSingle(String text, EmbeddingConfig cfg) {
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            List<float[]> batch = embeddingConfigService.requestEmbeddings(
                    cfg.getBaseUrl(),
                    embeddingConfigService.decryptKey(cfg.getApiKeyEncrypted()),
                    embeddingConfigService.decryptHeaders(cfg),
                    cfg.getModelName(),
                    List.of(text),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(8));
            if (batch != null && !batch.isEmpty()) {
                return normalize(batch.get(0));
            }
        }
        float[] v = localEmbeddingService.embed(text);
        return v == null ? null : normalize(v);
    }

    static Set<String> routeByKeywords(String message, boolean isSuperAdmin) {
        String lower = message.toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        for (ToolGroup g : GROUPS) {
            if (g.superAdminOnly() && !isSuperAdmin) {
                continue;
            }
            // "使用文档/平台文档" 不要误触发 knowledge 分组
            if ("knowledge".equals(g.id())
                    && (lower.contains("使用文档") || lower.contains("平台文档"))
                    && !lower.contains("知识库") && !lower.contains("faq") && !lower.contains("召回") && !lower.contains("索引")) {
                continue;
            }
            if (g.keywordPattern().matcher(lower).find()) {
                matched.add(g.id());
            }
        }
        return matched;
    }

    static String groupFromPageContext(Map<String, String> context, boolean isSuperAdmin) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        String resType = context.get("resourceType");
        if ("AGENT".equalsIgnoreCase(resType)) {
            return "agents";
        }
        if ("KNOWLEDGE_BASE".equalsIgnoreCase(resType)) {
            return "knowledge";
        }
        String page = context.get("page");
        if (page == null || page.isBlank()) {
            return null;
        }
        String p = page.trim().toLowerCase(Locale.ROOT);
        if (p.equals("agents") || p.equals("debug")) {
            return "agents";
        }
        if (p.startsWith("knowledge")) {
            return "knowledge";
        }
        if (p.equals("templates")) {
            return "templates";
        }
        if (p.equals("tools")) {
            return "tools_mcp";
        }
        if (p.equals("skills")) {
            return "skills";
        }
        if (p.startsWith("gateway")) {
            return "gateway";
        }
        if (p.equals("overview")) {
            return "usage";
        }
        if (p.equals("security")) {
            return "security";
        }
        if ((p.equals("users") || p.equals("roles")) && isSuperAdmin) {
            return "users_roles";
        }
        return null;
    }

    private static boolean looksLikeFollowUp(String message) {
        return message.length() <= 18 || FOLLOWUP_CUE.matcher(message).find();
    }

    private static String enrichQueryWithContext(String message, Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return message;
        }
        String resType = context.get("resourceType");
        if ("AGENT".equalsIgnoreCase(resType) && looksLikeFollowUp(message)) {
            return message + "（当前智能体）";
        }
        if ("KNOWLEDGE_BASE".equalsIgnoreCase(resType) && looksLikeFollowUp(message)) {
            return message + "（当前知识库）";
        }
        return message;
    }

    private static String configKey(EmbeddingConfig cfg) {
        return cfg.getId() + ":" + cfg.getModelName() + ":" + cfg.getUpdatedAt();
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static float[] normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) {
            sum += v * v;
        }
        if (sum <= 0.0) {
            return vec;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            out[i] = vec[i] * inv;
        }
        return out;
    }

    /** 供测试清理缓存与冷却状态 */
    void clearCache() {
        cachedConfigKey = null;
        cachedGroupVectors = null;
        embeddingFailedUntilMs = 0L;
    }
}
