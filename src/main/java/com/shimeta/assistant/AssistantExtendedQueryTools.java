package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.AgentTemplate;
import com.shimeta.model.AlertRule;
import com.shimeta.model.AuditEvent;
import com.shimeta.model.ClientCredential;
import com.shimeta.model.ConversationDetail;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.PageResult;
import com.shimeta.model.PlatformToolView;
import com.shimeta.model.RoleDto;
import com.shimeta.model.UserSummaryDto;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.dto.RetrievalTestRequest;
import com.shimeta.rag.engine.RetrievalResult;
import com.shimeta.repository.AuditEventRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentConversationService;
import com.shimeta.service.AgentPromptVersionService;
import com.shimeta.service.AgentService;
import com.shimeta.service.AgentTemplateService;
import com.shimeta.service.AlertService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.RagEvaluationService;
import com.shimeta.service.SecurityPlatformService;
import com.shimeta.service.ToolUsageService;
import com.shimeta.service.UserAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台 AI 助手的扩展只读工具（4.3 补齐查询能力）：
 * <ul>
 *   <li>{@code list_resources} / {@code get_resource}：按资源类型覆盖 12 类平台资源查询与详情；</li>
 *   <li>{@code run_retrieval_test}：对知识库直接发起召回测试并返回命中切片与得分；</li>
 *   <li>{@code get_agent_logs}：查看智能体近期对话与失败线索；</li>
 *   <li>{@code get_tool_usage}：统计工具调用次数、失败率与平均耗时。</li>
 * </ul>
 *
 * <p>安全边界：全部遵循 {@link CurrentActor} 角色与资源可见范围；绝不返回密钥、密码、
 * API Key 哈希/明文、Webhook URL/密钥、HTTP 工具接口地址或自定义请求头。
 */
@Component
public class AssistantExtendedQueryTools {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern FAILURE_CLUE = Pattern.compile(
            "(模型通道暂时没有返回|调用失败|请求失败|连接超时|timed out|http\\s*[45]\\d\\d|服务异常|无法连接|未配置.*通道|错误[:：])",
            Pattern.CASE_INSENSITIVE);

    static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
            "http_tool", "template", "prompt_version", "api_key", "terminal",
            "guardrail_policy", "audit_log", "alert", "rag_eval_run", "faq",
            "user", "role");

    private final AssistantQueryTools queryTools;
    private final AgentService agentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentTemplateService templateService;
    private final PlatformToolService platformToolService;
    private final AgentPromptVersionService promptVersionService;
    private final OpenApiKeyService openApiKeyService;
    private final SecurityPlatformService securityPlatformService;
    private final AuditEventRepository auditEventRepository;
    private final AlertService alertService;
    private final RagEvaluationService ragEvaluationService;
    private final UserAdminService userAdminService;
    private final AgentConversationService conversationService;
    private final ToolUsageService toolUsageService;

    @Autowired
    public AssistantExtendedQueryTools(AssistantQueryTools queryTools,
                                       AgentService agentService,
                                       KnowledgeBaseService knowledgeBaseService,
                                       AgentTemplateService templateService,
                                       @Autowired(required = false) PlatformToolService platformToolService,
                                       @Autowired(required = false) AgentPromptVersionService promptVersionService,
                                       @Autowired(required = false) OpenApiKeyService openApiKeyService,
                                       @Autowired(required = false) SecurityPlatformService securityPlatformService,
                                       @Autowired(required = false) AuditEventRepository auditEventRepository,
                                       @Autowired(required = false) AlertService alertService,
                                       @Autowired(required = false) RagEvaluationService ragEvaluationService,
                                       @Autowired(required = false) UserAdminService userAdminService,
                                       @Autowired(required = false) AgentConversationService conversationService,
                                       @Autowired(required = false) ToolUsageService toolUsageService) {
        this.queryTools = queryTools;
        this.agentService = agentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.templateService = templateService;
        this.platformToolService = platformToolService;
        this.promptVersionService = promptVersionService;
        this.openApiKeyService = openApiKeyService;
        this.securityPlatformService = securityPlatformService;
        this.auditEventRepository = auditEventRepository;
        this.alertService = alertService;
        this.ragEvaluationService = ragEvaluationService;
        this.userAdminService = userAdminService;
        this.conversationService = conversationService;
        this.toolUsageService = toolUsageService;
    }

    public List<AssistantTool> tools() {
        return List.of(
                AssistantTool.read("list_resources", "查询平台资源列表",
                        "按类型查询平台各类资源列表：http_tool（内置与自定义 HTTP 工具）、template（场景模板）、prompt_version（智能体提示词历史版本，需传 parent 指定智能体）、"
                                + "api_key（开放凭证元数据）、terminal（接入终端）、guardrail_policy（护栏策略）、audit_log（安全审计日志）、alert（告警规则）、"
                                + "rag_eval_run（知识库检索评测记录，需传 parent 指定知识库）、faq（知识库 FAQ 列表，需传 parent 指定知识库）、"
                                + "user（用户列表，仅超管）、role（角色与权限列表，仅超管）。",
                        schema(Map.of(
                                "type", enumProp("资源类型",
                                        "http_tool", "template", "prompt_version", "api_key", "terminal",
                                        "guardrail_policy", "audit_log", "alert", "rag_eval_run", "faq", "user", "role"),
                                "parent", prop("string", "父资源名称或 ID：prompt_version 传智能体，faq / rag_eval_run 传知识库"),
                                "keyword", prop("string", "名称、问题、动作或用户名关键词，可选"),
                                "status", prop("string", "状态过滤，可选"),
                                "filters", Map.of("type", "object", "description", "附加过滤条件（如 category、role、riskLevel、result、enabled、parent、keyword、limit），可选"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20")), "type"),
                        (args, ctx) -> listResources(args, ctx.actor())),
                AssistantTool.read("get_resource", "查看单个资源详情",
                        "按类型和 ID（或名称）查看单个平台资源的脱敏详情（支持与 list_resources 相同的 12 类资源；不返回任何密钥、密码或敏感地址）。",
                        schema(Map.of(
                                "type", enumProp("资源类型",
                                        "http_tool", "template", "prompt_version", "api_key", "terminal",
                                        "guardrail_policy", "audit_log", "alert", "rag_eval_run", "faq", "user", "role"),
                                "id", prop("string", "资源 ID、编码或名称（guardrail_policy 可不传）"),
                                "parent", prop("string", "父资源名称或 ID：prompt_version 传智能体，faq / rag_eval_run 传知识库")), "type"),
                        (args, ctx) -> getResource(args, ctx.actor())),
                AssistantTool.read("run_retrieval_test", "执行知识库召回测试",
                        "对指定知识库执行一次召回测试，返回命中的文档切片、来源、综合分/向量分/关键词分/重排分和耗时。用户问\"知识库搜 XX 能搜到什么\"\"帮我测一下召回效果\"时使用。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "query", prop("string", "测试检索的问句或关键词"),
                                "topK", prop("integer", "返回条数，默认 5，最大 10")), "knowledgeBase", "query"),
                        (args, ctx) -> runRetrievalTest(args, ctx.actor())),
                AssistantTool.read("get_agent_logs", "查询智能体对话日志",
                        "查询指定智能体近期的对话会话列表、耗时、模型、Token 及疑似失败回复线索。用户问\"最近对话记录\"\"日志里有没有报错\"\"最近几次回复怎么样\"时使用。",
                        schema(Map.of(
                                "agent", prop("string", "智能体的 ID、编码或名称"),
                                "range", enumProp("时间范围：today 今天，7days 近 7 天（默认），30days 近 30 天", "today", "7days", "30days"),
                                "keyword", prop("string", "会话标题或账号关键词，可选"),
                                "limit", prop("integer", "返回数量，默认 10，最大 20")), "agent"),
                        (args, ctx) -> getAgentLogs(args, ctx.actor())),
                AssistantTool.read("get_tool_usage", "查询工具调用统计",
                        "统计平台各工具（内置/自定义 HTTP/MCP）的今日、本月、累计调用次数、失败次数、成功率、平均耗时和预估费用。可按单个智能体筛选。",
                        schema(Map.of(
                                "agent", prop("string", "可选：智能体 ID、编码或名称，不传则统计当前用户可见的全部智能体"))),
                        (args, ctx) -> getToolUsage(args, ctx.actor()))
        );
    }

    // ==================== list_resources ====================

    Map<String, Object> listResources(JsonNode args, CurrentActor actor) {
        String type = normalizeType(text(args, "type"));
        if (type == null) {
            throw new IllegalArgumentException("不支持的资源类型，可选：" + String.join("、", SUPPORTED_RESOURCE_TYPES));
        }
        int limit = limit(args);
        return switch (type) {
            case "http_tool" -> listHttpTools(args, limit);
            case "template" -> listTemplates(args, actor, limit);
            case "prompt_version" -> listPromptVersions(args, actor, limit);
            case "api_key" -> listApiKeys(args, actor, limit);
            case "terminal" -> listTerminals(args, actor, limit);
            case "guardrail_policy" -> getGuardrailPolicy(actor);
            case "audit_log" -> listAuditLogs(args, actor, limit);
            case "alert" -> listAlerts(args, actor, limit);
            case "rag_eval_run" -> listRagEvalRuns(args, actor, limit);
            case "faq" -> listFaqs(args, actor, limit);
            case "user" -> listUsers(args, actor, limit);
            case "role" -> listRoles(actor);
            default -> throw new IllegalArgumentException("不支持的资源类型：" + type);
        };
    }

    // ==================== get_resource ====================

    Map<String, Object> getResource(JsonNode args, CurrentActor actor) {
        String type = normalizeType(text(args, "type"));
        if (type == null) {
            throw new IllegalArgumentException("不支持的资源类型，可选：" + String.join("、", SUPPORTED_RESOURCE_TYPES));
        }
        if ("guardrail_policy".equals(type)) {
            return getGuardrailPolicy(actor);
        }
        String id = text(args, "id");
        if (id == null) {
            throw new IllegalArgumentException("请提供要查看的资源 id 或名称");
        }
        return switch (type) {
            case "http_tool" -> getHttpTool(id);
            case "template" -> getTemplate(id, actor);
            case "prompt_version" -> getPromptVersion(args, id, actor);
            case "api_key" -> getApiKey(id, actor);
            case "terminal" -> getTerminal(id, actor);
            case "audit_log" -> getAuditLog(id, actor);
            case "alert" -> getAlert(id, actor);
            case "rag_eval_run" -> getRagEvalRun(args, id, actor);
            case "faq" -> getFaq(args, id, actor);
            case "user" -> getUser(id, actor);
            case "role" -> getRole(id, actor);
            default -> throw new IllegalArgumentException("不支持的资源类型：" + type);
        };
    }

    // ---------- 1. http_tool ----------

    private Map<String, Object> listHttpTools(JsonNode args, int limit) {
        requireModule(platformToolService, "工具管理模块");
        String keyword = filterText(args, "keyword");
        String category = filterText(args, "category");
        Boolean builtinFilter = filterBoolean(args, "builtin");
        String lowerKw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<PlatformToolView> all = platformToolService.list();
        List<PlatformToolView> filtered = all.stream()
                .filter(t -> category == null || category.equalsIgnoreCase(t.getCategory()))
                .filter(t -> builtinFilter == null || builtinFilter.equals(Boolean.TRUE.equals(t.getBuiltin())))
                .filter(t -> lowerKw == null
                        || containsIgnoreCase(t.getCode(), lowerKw)
                        || containsIgnoreCase(t.getName(), lowerKw)
                        || containsIgnoreCase(t.getTitle(), lowerKw)
                        || containsIgnoreCase(t.getDescription(), lowerKw))
                .toList();
        List<Map<String, Object>> items = filtered.stream().limit(limit).map(this::httpToolSummary).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "http_tool");
        res.put("total", filtered.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "当前没有匹配的工具（total=0），可在「构建 → 工具」查看或新增自定义 HTTP 工具。");
        }
        return ok(res);
    }

    private Map<String, Object> getHttpTool(String ref) {
        requireModule(platformToolService, "工具管理模块");
        List<PlatformToolView> all = platformToolService.list();
        PlatformToolView match = all.stream()
                .filter(t -> equalsAnyIgnoreCase(ref, t.getId(), t.getCode(), t.getName(), t.getTitle()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到工具「" + ref + "」"));
        Map<String, Object> detail = httpToolSummary(match);
        detail.put("help", truncate(match.getHelp(), 240));
        return ok(Map.of("type", "http_tool", "item", detail));
    }

    private Map<String, Object> httpToolSummary(PlatformToolView t) {
        // 严格白名单：不返回 config（内含 HTTP 接口 URL、自定义 Header 与密钥）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("code", t.getCode());
        m.put("name", t.getName());
        m.put("title", t.getTitle() != null ? t.getTitle() : t.getName());
        m.put("category", t.getCategory());
        m.put("builtin", Boolean.TRUE.equals(t.getBuiltin()));
        m.put("enabled", !Boolean.FALSE.equals(t.getEnabled()));
        m.put("hasApiKey", t.isHasApiKey());
        m.put("unitPrice", t.getUnitPrice());
        m.put("description", truncate(t.getDescription(), 140));
        m.put("updatedAt", format(t.getUpdatedAt()));
        return m;
    }

    // ---------- 2. template ----------

    private Map<String, Object> listTemplates(JsonNode args, CurrentActor actor, int limit) {
        List<AgentTemplate> templates = templateService.list(filterText(args, "keyword"), filterText(args, "category"), actor);
        List<Map<String, Object>> items = templates.stream().limit(limit).map(this::templateSummary).toList();
        return ok(Map.of("type", "template", "total", templates.size(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> getTemplate(String ref, CurrentActor actor) {
        List<AgentTemplate> templates = templateService.list(null, null, actor);
        AgentTemplate t = templates.stream()
                .filter(item -> equalsAnyIgnoreCase(ref, item.getId(), item.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到场景模板「" + ref + "」"));
        Map<String, Object> detail = templateSummary(t);
        detail.put("systemPromptSummary", truncate(t.getSystemPrompt(), 300));
        detail.put("temperature", t.getTemperature());
        detail.put("maxTokens", t.getMaxTokens());
        return ok(Map.of("type", "template", "item", detail));
    }

    private Map<String, Object> templateSummary(AgentTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("category", t.getCategory());
        m.put("description", truncate(t.getDescription(), 120));
        m.put("builtin", Boolean.TRUE.equals(t.getIsBuiltin()));
        return m;
    }

    // ---------- 3. prompt_version ----------

    private Map<String, Object> listPromptVersions(JsonNode args, CurrentActor actor, int limit) {
        requireModule(promptVersionService, "提示词版本管理模块");
        String agentRef = resolveParentRef(args, "agent");
        if (agentRef == null) {
            throw new IllegalArgumentException("查询提示词历史版本需要指定智能体（parent 参数）");
        }
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(agentRef, actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = match.agent();
        List<Map<String, Object>> versions = promptVersionService.list(agent.getId(), actor);
        List<Map<String, Object>> items = versions.stream().limit(limit).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "prompt_version");
        res.put("agent", Map.of("id", agent.getId(), "name", agent.getName(), "link", AssistantQueryTools.agentLink(agent.getId())));
        res.put("total", versions.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "该智能体暂无历史提示词版本记录。");
        }
        return ok(res);
    }

    private Map<String, Object> getPromptVersion(JsonNode args, String versionRef, CurrentActor actor) {
        requireModule(promptVersionService, "提示词版本管理模块");
        String agentRef = resolveParentRef(args, "agent");
        if (agentRef == null) {
            throw new IllegalArgumentException("查看提示词版本详情需要指定智能体（parent 参数）");
        }
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(agentRef, actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = match.agent();
        String targetVersionId = versionRef;
        // 允许传版本号（如 "2" 或 "v2"）
        if (versionRef.matches("(?i)v?\\d+")) {
            int versionNo = Integer.parseInt(versionRef.replaceAll("(?i)^v", ""));
            List<Map<String, Object>> all = promptVersionService.list(agent.getId(), actor);
            targetVersionId = all.stream()
                    .filter(v -> Objects.equals(String.valueOf(v.get("versionNo")), String.valueOf(versionNo)))
                    .map(v -> String.valueOf(v.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未找到第 " + versionNo + " 版提示词"));
        }
        Map<String, Object> detail = new LinkedHashMap<>(promptVersionService.get(agent.getId(), targetVersionId, actor));
        if (detail.get("systemPrompt") instanceof String sp && sp.length() > 1500) {
            detail.put("systemPrompt", sp.substring(0, 1500) + "…（过长已截断）");
        }
        return ok(Map.of("type", "prompt_version",
                "agent", Map.of("id", agent.getId(), "name", agent.getName(), "link", AssistantQueryTools.agentLink(agent.getId())),
                "item", detail));
    }

    // ---------- 4. api_key ----------

    private Map<String, Object> listApiKeys(JsonNode args, CurrentActor actor, int limit) {
        requireModule(openApiKeyService, "开放凭证模块");
        String keyword = filterText(args, "keyword");
        String status = filterText(args, "status");
        String lowerKw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<OpenApiKey> keys = openApiKeyService.listForActor(actor);
        List<OpenApiKey> filtered = keys.stream()
                .filter(k -> status == null || status.equalsIgnoreCase(k.getStatus()))
                .filter(k -> lowerKw == null
                        || containsIgnoreCase(k.getName(), lowerKw)
                        || containsIgnoreCase(k.getKeyPrefix(), lowerKw))
                .toList();
        List<Map<String, Object>> items = filtered.stream().limit(limit).map(this::apiKeySummary).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "api_key");
        res.put("scope", actor.isSuperAdmin() ? "全站开放凭证元数据（不含密钥明文与哈希）" : "本人名下开放凭证元数据（不含密钥明文与哈希）");
        res.put("total", filtered.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "当前没有匹配的开放 API 凭证（total=0）。");
        }
        return ok(res);
    }

    private Map<String, Object> getApiKey(String ref, CurrentActor actor) {
        requireModule(openApiKeyService, "开放凭证模块");
        OpenApiKey key = openApiKeyService.listForActor(actor).stream()
                .filter(k -> equalsAnyIgnoreCase(ref, k.getId(), k.getName(), k.getKeyPrefix()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到开放 API 凭证「" + ref + "」（或当前账号无权查看）"));
        return ok(Map.of("type", "api_key", "item", apiKeySummary(key)));
    }

    private Map<String, Object> apiKeySummary(OpenApiKey k) {
        // 严禁返回 keyHash / 明文
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("keyPrefix", k.getKeyPrefix());
        m.put("status", k.getStatus());
        m.put("scopes", k.getScopes());
        m.put("agentScope", k.getAgentScope());
        m.put("ipAllowlist", k.getIpAllowlist());
        m.put("rateLimitRpm", k.getRateLimitRpm());
        m.put("dailyTokenQuota", k.getDailyTokenQuota());
        m.put("expiresAt", format(k.getExpiresAt()));
        m.put("lastUsedAt", format(k.getLastUsedAt()));
        m.put("createdAt", format(k.getCreatedAt()));
        return m;
    }

    // ---------- 5. terminal ----------

    private Map<String, Object> listTerminals(JsonNode args, CurrentActor actor, int limit) {
        requireModule(securityPlatformService, "接入终端模块");
        String keyword = filterText(args, "keyword");
        String status = filterText(args, "status");
        String lowerKw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<ClientCredential> clients = securityPlatformService.listClients(actor);
        List<ClientCredential> filtered = clients.stream()
                .filter(c -> status == null || status.equalsIgnoreCase(c.getStatus()))
                .filter(c -> lowerKw == null
                        || containsIgnoreCase(c.getLabel(), lowerKw)
                        || containsIgnoreCase(c.getClientId(), lowerKw)
                        || containsIgnoreCase(c.getClientType(), lowerKw))
                .toList();
        List<Map<String, Object>> items = filtered.stream().limit(limit).map(this::terminalSummary).toList();
        return ok(Map.of("type", "terminal", "total", filtered.size(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> getTerminal(String ref, CurrentActor actor) {
        requireModule(securityPlatformService, "接入终端模块");
        ClientCredential c = securityPlatformService.listClients(actor).stream()
                .filter(item -> equalsAnyIgnoreCase(ref, item.getId(), item.getClientId(), item.getLabel()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到接入终端「" + ref + "」（或当前账号无权查看）"));
        return ok(Map.of("type", "terminal", "item", terminalSummary(c)));
    }

    private Map<String, Object> terminalSummary(ClientCredential c) {
        // 严禁返回 clientIdHash
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("clientType", c.getClientType());
        m.put("clientId", c.getClientId());
        m.put("label", c.getLabel());
        m.put("status", c.getStatus());
        m.put("agentScope", c.getAgentScope());
        m.put("rateLimitRpm", c.getRateLimitRpm());
        m.put("dailyTokenQuota", c.getDailyTokenQuota());
        m.put("owner", c.getOwnerUsername());
        m.put("expiresAt", format(c.getExpiresAt()));
        m.put("lastSeenAt", format(c.getLastSeenAt()));
        return m;
    }

    // ---------- 6. guardrail_policy ----------

    private Map<String, Object> getGuardrailPolicy(CurrentActor actor) {
        requireModule(securityPlatformService, "护栏策略模块");
        GuardrailPolicy p = securityPlatformService.loadPolicy(actor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", "GLOBAL".equalsIgnoreCase(p.getOwnerId()) ? "全局护栏策略" : "当前账号护栏策略");
        m.put("clientPolicy", p.getClientPolicy());
        m.put("defaultRpm", p.getDefaultRpm());
        m.put("defaultDailyTokens", p.getDefaultDailyTokens());
        m.put("maxInputChars", p.getMaxInputChars());
        m.put("maxHistoryTurns", p.getMaxHistoryTurns());
        m.put("piiMask", p.getPiiMask());
        m.put("sensitiveWordCount", p.getSensitiveWords() == null ? 0 : p.getSensitiveWords().size());
        m.put("sensitiveAction", p.getSensitiveAction());
        m.put("promptInjection", p.getPromptInjection());
        m.put("outputGuard", p.getOutputGuard());
        m.put("allowedHours", p.getAllowedHours());
        m.put("killSwitch", p.getKillSwitch());
        m.put("updatedAt", format(p.getUpdatedAt()));
        return ok(Map.of("type", "guardrail_policy", "item", m));
    }

    // ---------- 7. audit_log ----------

    private Map<String, Object> listAuditLogs(JsonNode args, CurrentActor actor, int limit) {
        requireModule(auditEventRepository, "审计日志模块");
        String riskLevel = filterText(args, "riskLevel");
        String result = filterText(args, "result");
        String keyword = filterText(args, "keyword");
        String lowerKw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        PageRequest pageable = PageRequest.of(0, Math.min(100, Math.max(limit * 3, 30)));
        Page<AuditEvent> page;
        if (actor.isSuperAdmin()) {
            if (riskLevel != null) {
                page = auditEventRepository.findByRiskLevelOrderByOccurredAtDesc(riskLevel.toUpperCase(Locale.ROOT), pageable);
            } else if (result != null) {
                page = auditEventRepository.findByResultOrderByOccurredAtDesc(result.toUpperCase(Locale.ROOT), pageable);
            } else {
                page = auditEventRepository.findAllByOrderByOccurredAtDesc(pageable);
            }
        } else {
            if (riskLevel != null) {
                page = auditEventRepository.findByOwnerIdAndRiskLevelOrderByOccurredAtDesc(actor.getUserId(), riskLevel.toUpperCase(Locale.ROOT), pageable);
            } else if (result != null) {
                page = auditEventRepository.findByOwnerIdAndResultOrderByOccurredAtDesc(actor.getUserId(), result.toUpperCase(Locale.ROOT), pageable);
            } else {
                page = auditEventRepository.findByOwnerIdOrderByOccurredAtDesc(actor.getUserId(), pageable);
            }
        }

        List<AuditEvent> events = page == null ? List.of() : page.getContent();
        List<AuditEvent> filtered = events.stream()
                .filter(e -> result == null || result.equalsIgnoreCase(e.getResult()))
                .filter(e -> riskLevel == null || riskLevel.equalsIgnoreCase(e.getRiskLevel()))
                .filter(e -> lowerKw == null
                        || containsIgnoreCase(e.getAction(), lowerKw)
                        || containsIgnoreCase(e.getResourceType(), lowerKw)
                        || containsIgnoreCase(e.getResourceId(), lowerKw)
                        || containsIgnoreCase(e.getReasonCode(), lowerKw))
                .toList();
        List<Map<String, Object>> items = filtered.stream().limit(limit).map(this::auditEventSummary).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "audit_log");
        res.put("scope", actor.isSuperAdmin() ? "全站审计日志" : "本人相关审计日志");
        res.put("total", page != null && lowerKw == null ? page.getTotalElements() : filtered.size());
        res.put("returned", items.size());
        res.put("items", items);
        return ok(res);
    }

    private Map<String, Object> getAuditLog(String id, CurrentActor actor) {
        requireModule(auditEventRepository, "审计日志模块");
        AuditEvent event = auditEventRepository.findById(id)
                .filter(e -> actor.isSuperAdmin() || Objects.equals(actor.getUserId(), e.getOwnerId()))
                .orElseThrow(() -> new IllegalArgumentException("未找到审计日志「" + id + "」（或当前账号无权查看）"));
        Map<String, Object> detail = auditEventSummary(event);
        if (event.getSanitizedDiff() != null && !event.getSanitizedDiff().isBlank()) {
            detail.put("sanitizedDiff", truncate(event.getSanitizedDiff(), 240));
        }
        return ok(Map.of("type", "audit_log", "item", detail));
    }

    private Map<String, Object> auditEventSummary(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("occurredAt", format(e.getOccurredAt()));
        m.put("actorType", e.getActorType());
        m.put("action", e.getAction());
        m.put("resourceType", e.getResourceType());
        m.put("resourceId", e.getResourceId());
        m.put("result", e.getResult());
        m.put("riskLevel", e.getRiskLevel());
        m.put("reasonCode", e.getReasonCode());
        m.put("requestId", e.getRequestId());
        return m;
    }

    // ---------- 8. alert ----------

    private Map<String, Object> listAlerts(JsonNode args, CurrentActor actor, int limit) {
        requireModule(alertService, "告警规则模块");
        String keyword = filterText(args, "keyword");
        Boolean enabledFilter = filterBoolean(args, "enabled");
        String lowerKw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);

        List<AlertRule> rules = alertService.listRules(actor);
        List<AlertRule> filtered = rules.stream()
                .filter(r -> enabledFilter == null || enabledFilter.equals(Boolean.TRUE.equals(r.getEnabled())))
                .filter(r -> lowerKw == null
                        || containsIgnoreCase(r.getName(), lowerKw)
                        || containsIgnoreCase(r.getMetric(), lowerKw))
                .toList();
        List<Map<String, Object>> items = filtered.stream().limit(limit).map(this::alertSummary).toList();
        return ok(Map.of("type", "alert", "total", filtered.size(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> getAlert(String ref, CurrentActor actor) {
        requireModule(alertService, "告警规则模块");
        AlertRule rule = alertService.listRules(actor).stream()
                .filter(r -> equalsAnyIgnoreCase(ref, r.getId(), r.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到告警规则「" + ref + "」（或当前账号无权查看）"));
        return ok(Map.of("type", "alert", "item", alertSummary(rule)));
    }

    private Map<String, Object> alertSummary(AlertRule r) {
        // 严禁返回 webhookUrl 和 webhookSecret
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("metric", r.getMetric());
        m.put("threshold", r.getThreshold());
        m.put("timeWindowMinutes", r.getTimeWindowMinutes());
        m.put("silenceMinutes", r.getSilenceMinutes());
        m.put("enabled", Boolean.TRUE.equals(r.getEnabled()));
        m.put("webhookConfigured", r.getWebhookUrl() != null && !r.getWebhookUrl().isBlank());
        m.put("lastTriggeredAt", format(r.getLastTriggeredAt()));
        return m;
    }

    // ---------- 9. rag_eval_run ----------

    private Map<String, Object> listRagEvalRuns(JsonNode args, CurrentActor actor, int limit) {
        requireModule(ragEvaluationService, "知识库检索评测模块");
        String kbRef = resolveParentRef(args, "knowledgeBase");
        if (kbRef == null) {
            throw new IllegalArgumentException("查询检索评测记录需要指定知识库（parent 参数）");
        }
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
        List<RagEvaluationService.RunView> runs = ragEvaluationService.listRuns(kb.getId(), actor);
        List<Map<String, Object>> items = runs.stream().limit(limit).map(this::ragEvalSummary).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "rag_eval_run");
        res.put("knowledgeBase", Map.of("id", kb.getId(), "name", kb.getName(), "link", AssistantQueryTools.knowledgeBaseLink(kb.getId())));
        res.put("total", runs.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "该知识库暂无检索评测记录，可在「数据 → 自建知识库 → 检索评测」新建评测集并运行。");
        }
        return ok(res);
    }

    private Map<String, Object> getRagEvalRun(JsonNode args, String runId, CurrentActor actor) {
        requireModule(ragEvaluationService, "知识库检索评测模块");
        String kbRef = resolveParentRef(args, "knowledgeBase");
        if (kbRef == null) {
            throw new IllegalArgumentException("查看检索评测详情需要指定知识库（parent 参数）");
        }
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
        RagEvaluationService.RunView run = ragEvaluationService.getRun(kb.getId(), runId, actor);
        Map<String, Object> detail = ragEvalSummary(run);
        detail.put("config", run.config());
        if (run.errorMessage() != null && !run.errorMessage().isBlank()) {
            detail.put("errorMessage", truncate(run.errorMessage(), 200));
        }
        return ok(Map.of("type", "rag_eval_run",
                "knowledgeBase", Map.of("id", kb.getId(), "name", kb.getName(), "link", AssistantQueryTools.knowledgeBaseLink(kb.getId())),
                "item", detail));
    }

    private Map<String, Object> ragEvalSummary(RagEvaluationService.RunView r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("label", r.label());
        m.put("engine", r.engine());
        m.put("status", r.status());
        m.put("caseCount", r.caseCount());
        m.put("completedCount", r.completedCount());
        m.put("errorCount", r.errorCount());
        m.put("topK", r.topK());
        m.put("hitAtTopK", r.hitAtTopK());
        m.put("hitAt5", r.hitAt5());
        m.put("recallAt10", r.recallAt10());
        m.put("ndcgAt10", r.ndcgAt10());
        m.put("avgLatencyMs", r.avgLatencyMs());
        m.put("createdAt", format(r.createdAt()));
        return m;
    }

    // ---------- 10. faq ----------

    private Map<String, Object> listFaqs(JsonNode args, CurrentActor actor, int limit) {
        String kbRef = resolveParentRef(args, "knowledgeBase");
        if (kbRef == null) {
            throw new IllegalArgumentException("查询 FAQ 列表需要指定知识库（parent 参数）");
        }
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
        String keyword = filterText(args, "keyword");
        String category = filterText(args, "category");
        PageResult<KnowledgeFaq> page = knowledgeBaseService.searchFaqs(kb.getId(), keyword, category, 1, limit, actor);
        List<KnowledgeFaq> records = page != null && page.getRecords() != null ? page.getRecords() : List.of();
        List<Map<String, Object>> items = records.stream().map(this::faqSummary).toList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", "faq");
        res.put("knowledgeBase", Map.of("id", kb.getId(), "name", kb.getName(), "link", AssistantQueryTools.knowledgeBaseLink(kb.getId())));
        res.put("total", page != null ? page.getTotal() : items.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "当前筛选条件下该知识库没有匹配的 FAQ（total=0）。");
        }
        return ok(res);
    }

    private Map<String, Object> getFaq(JsonNode args, String ref, CurrentActor actor) {
        String kbRef = resolveParentRef(args, "knowledgeBase");
        if (kbRef == null) {
            throw new IllegalArgumentException("查看 FAQ 详情需要指定知识库（parent 参数）");
        }
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
        PageResult<KnowledgeFaq> page = knowledgeBaseService.searchFaqs(kb.getId(), null, null, 1, 200, actor);
        List<KnowledgeFaq> records = page != null && page.getRecords() != null ? page.getRecords() : List.of();
        KnowledgeFaq faq = records.stream()
                .filter(f -> equalsAnyIgnoreCase(ref, f.getId(), f.getQuestion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 FAQ「" + ref + "」"));
        Map<String, Object> detail = faqSummary(faq);
        detail.put("answer", truncate(faq.getAnswer(), 1000));
        return ok(Map.of("type", "faq",
                "knowledgeBase", Map.of("id", kb.getId(), "name", kb.getName(), "link", AssistantQueryTools.knowledgeBaseLink(kb.getId())),
                "item", detail));
    }

    private Map<String, Object> faqSummary(KnowledgeFaq f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("question", truncate(f.getQuestion(), 140));
        m.put("answer", truncate(f.getAnswer(), 240));
        m.put("category", f.getCategory());
        m.put("contentType", f.getContentType());
        m.put("hasImages", f.getImageUrls() != null && !f.getImageUrls().isBlank() && !"[]".equals(f.getImageUrls().trim()));
        m.put("enabled", !Boolean.FALSE.equals(f.getEnabled()));
        m.put("hitCount", f.getHitCount() == null ? 0L : f.getHitCount());
        m.put("updatedAt", format(f.getUpdatedAt()));
        return m;
    }

    // ---------- 11. user (SUPER_ADMIN ONLY) ----------

    private Map<String, Object> listUsers(JsonNode args, CurrentActor actor, int limit) {
        requireSuperAdmin(actor, "查看用户列表");
        requireModule(userAdminService, "用户管理模块");
        List<UserSummaryDto> users = userAdminService.listUsers(
                filterText(args, "keyword"), filterText(args, "role"), filterText(args, "status"));
        List<Map<String, Object>> items = users.stream().limit(limit).map(this::userSummary).toList();
        return ok(Map.of("type", "user", "total", users.size(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> getUser(String ref, CurrentActor actor) {
        requireSuperAdmin(actor, "查看用户详情");
        requireModule(userAdminService, "用户管理模块");
        UserSummaryDto user = userAdminService.listUsers(null, null, null).stream()
                .filter(u -> equalsAnyIgnoreCase(ref, u.getId(), u.getUsername(), u.getNickname()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到用户「" + ref + "」"));
        return ok(Map.of("type", "user", "item", userSummary(user)));
    }

    private Map<String, Object> userSummary(UserSummaryDto u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("role", u.getRole());
        m.put("roleName", u.getRoleName());
        m.put("status", u.getStatus());
        m.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        m.put("activeKeyCount", u.getKeyCount() == null ? 0L : u.getKeyCount());
        m.put("activeClientCount", u.getClientCount() == null ? 0L : u.getClientCount());
        m.put("todayCalls", u.getTodayCalls() == null ? 0L : u.getTodayCalls());
        m.put("createdAt", format(u.getCreatedAt()));
        return m;
    }

    // ---------- 12. role (SUPER_ADMIN ONLY) ----------

    private Map<String, Object> listRoles(CurrentActor actor) {
        requireSuperAdmin(actor, "查看角色列表");
        requireModule(userAdminService, "角色与权限模块");
        List<RoleDto> roles = userAdminService.getRolesSummary();
        List<Map<String, Object>> items = roles.stream().map(this::roleSummary).toList();
        return ok(Map.of("type", "role", "total", items.size(), "returned", items.size(), "items", items));
    }

    private Map<String, Object> getRole(String ref, CurrentActor actor) {
        requireSuperAdmin(actor, "查看角色详情");
        requireModule(userAdminService, "角色与权限模块");
        RoleDto role = userAdminService.getRolesSummary().stream()
                .filter(r -> equalsAnyIgnoreCase(ref, r.getCode(), r.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到角色「" + ref + "」"));
        return ok(Map.of("type", "role", "item", roleSummary(role)));
    }

    private Map<String, Object> roleSummary(RoleDto r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("description", r.getDescription());
        m.put("builtin", r.isBuiltin());
        m.put("userCount", r.getUserCount());
        m.put("permissions", r.getPermissions());
        return m;
    }

    // ==================== run_retrieval_test ====================

    Map<String, Object> runRetrievalTest(JsonNode args, CurrentActor actor) {
        String kbRef = text(args, "knowledgeBase");
        String query = text(args, "query");
        if (query == null) {
            throw new IllegalArgumentException("请提供要测试检索的问题或关键词（query）");
        }
        int topK = clampInt(args, "topK", DEFAULT_TOP_K, 1, MAX_TOP_K);
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);

        RetrievalTestRequest req = new RetrievalTestRequest();
        req.setQuery(query);
        req.setTopK(topK);
        req.setCacheEnabled(false);

        RetrievalResult result = knowledgeBaseService.testRetrieval(kb.getId(), req, actor);
        List<RetrievedChunk> chunks = result != null && result.chunks() != null ? result.chunks() : List.of();
        long latencyMs = result != null ? result.latencyMs() : 0L;

        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < Math.min(chunks.size(), topK); i++) {
            RetrievedChunk c = chunks.get(i);
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("rank", i + 1);
            hit.put("sourceName", c.sourceName());
            hit.put("score", round4(c.score()));
            if (c.vectorScore() != null) {
                hit.put("vectorScore", round4(c.vectorScore()));
            }
            if (c.keywordScore() != null) {
                hit.put("keywordScore", round4(c.keywordScore()));
            }
            if (c.rerankScore() != null) {
                hit.put("rerankScore", round4(c.rerankScore()));
            }
            if (c.matchType() != null) {
                hit.put("matchType", c.matchType());
            }
            if (c.pageNumber() != null) {
                hit.put("pageNumber", c.pageNumber());
            }
            hit.put("content", truncate(c.content(), 360));
            hits.add(hit);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "engine", "SPRING_AI".equalsIgnoreCase(kb.getProvider()) ? "平台内置引擎" : "Dify 外部引擎",
                "link", AssistantQueryTools.knowledgeBaseLink(kb.getId())));
        res.put("query", query);
        res.put("topK", topK);
        res.put("latencyMs", latencyMs);
        res.put("hitCount", hits.size());
        res.put("hits", hits);
        if (hits.isEmpty()) {
            res.put("note", "本次召回测试命中 0 条切片。可能原因：知识库为空/文档索引未完成、相似度阈值过高、或向量模型未激活/与索引不一致。");
        }
        return ok(res);
    }

    // ==================== get_agent_logs ====================

    Map<String, Object> getAgentLogs(JsonNode args, CurrentActor actor) {
        requireModule(conversationService, "智能体对话日志模块");
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = match.agent();
        String range = normalizeRange(text(args, "range"));
        String keyword = text(args, "keyword");
        int limit = limit(args);

        PageResult<AgentConversation> page = conversationService.listLogs(agent, actor, range, keyword, "updatedAt", "desc", 1, limit);
        List<AgentConversation> records = page != null && page.getRecords() != null ? page.getRecords() : List.of();

        int suspectedFailures = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            AgentConversation conv = records.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", conv.getId());
            item.put("title", truncate(conv.getTitle(), 80));
            item.put("account", conv.getAccount());
            item.put("messageCount", conv.getMessageCount());
            item.put("lastModel", conv.getLastModel());
            item.put("totalTokens", conv.getTotalTokens());
            if (conv.getUserFeedback() != null && !conv.getUserFeedback().isBlank()) {
                item.put("userFeedback", conv.getUserFeedback());
            }
            item.put("updatedAt", format(conv.getUpdatedAt()));

            // 展开最近 5 条会话的末轮消息摘要与异常线索，兼顾上下文长度与排查价值
            if (i < 5) {
                Optional<ConversationDetail> detailOpt = safeGetDetail(agent, actor, conv.getId());
                if (detailOpt.isPresent()) {
                    List<AgentConversationMessage> msgs = detailOpt.get().getMessages();
                    AgentConversationMessage lastUser = lastMessageByRole(msgs, "user");
                    AgentConversationMessage lastAssistant = lastMessageByRole(msgs, "assistant");
                    if (lastUser != null) {
                        item.put("lastUserMessage", truncate(lastUser.getContent(), 120));
                    }
                    if (lastAssistant != null) {
                        item.put("lastAssistantPreview", truncate(lastAssistant.getContent(), 160));
                        item.put("lastLatencyMs", lastAssistant.getLatencyMs());
                        boolean suspected = isSuspectedFailureReply(lastAssistant.getContent());
                        if (suspected) {
                            suspectedFailures++;
                            item.put("suspectedFailure", true);
                        }
                    }
                }
            }
            items.add(item);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("agent", Map.of("id", agent.getId(), "name", agent.getName(), "link", AssistantQueryTools.agentLink(agent.getId())));
        res.put("range", rangeLabel(range));
        res.put("total", page != null ? page.getTotal() : items.size());
        res.put("returned", items.size());
        res.put("suspectedFailureCount", suspectedFailures);
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "该时间范围内没有找到对话日志记录（total=0）。");
        }
        return ok(res);
    }

    // ==================== get_tool_usage ====================

    Map<String, Object> getToolUsage(JsonNode args, CurrentActor actor) {
        requireModule(toolUsageService, "工具调用统计模块");
        String agentRef = text(args, "agent");
        Agent targetAgent = null;
        List<String> agentScope = null;
        if (agentRef != null) {
            AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(agentRef, actor);
            if (match.agent() == null) {
                return match.response();
            }
            targetAgent = match.agent();
            agentScope = List.of(targetAgent.getId());
        } else if (!actor.isSuperAdmin()) {
            PageResult<Agent> visible = agentService.searchAgents(null, null, null, null, actor, 1, 500);
            agentScope = visible != null && visible.getRecords() != null
                    ? visible.getRecords().stream().map(Agent::getId).toList()
                    : List.of();
        }

        Map<String, ToolUsageService.ToolStats> statsMap = toolUsageService.summary(agentScope);
        Map<String, PlatformToolView> toolMeta = new LinkedHashMap<>();
        if (platformToolService != null) {
            try {
                for (PlatformToolView v : platformToolService.list()) {
                    if (v.getCode() != null) {
                        toolMeta.put(v.getCode(), v);
                    }
                }
            } catch (RuntimeException ignored) {
                // 元数据不可用时退回仅展示 toolCode
            }
        }

        List<Map<String, Object>> items = statsMap.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, ToolUsageService.ToolStats>>comparingLong(e -> e.getValue().month()).reversed()
                        .thenComparing(Comparator.<Map.Entry<String, ToolUsageService.ToolStats>>comparingLong(e -> e.getValue().total()).reversed())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_LIMIT)
                .map(entry -> formatToolUsageRow(entry.getKey(), entry.getValue(), toolMeta.get(entry.getKey())))
                .toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("scope", targetAgent != null
                ? "智能体「" + targetAgent.getName() + "」的工具调用统计"
                : (actor.isSuperAdmin() ? "全站工具调用统计" : "当前用户可见智能体的工具调用统计"));
        if (targetAgent != null) {
            res.put("agent", Map.of("id", targetAgent.getId(), "name", targetAgent.getName(),
                    "link", AssistantQueryTools.agentLink(targetAgent.getId())));
        }
        res.put("total", statsMap.size());
        res.put("returned", items.size());
        res.put("items", items);
        if (items.isEmpty()) {
            res.put("note", "当前范围内暂无工具调用记录（total=0）。");
        }
        return ok(res);
    }

    private Map<String, Object> formatToolUsageRow(String code, ToolUsageService.ToolStats s, PlatformToolView meta) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("toolCode", code);
        if (meta != null) {
            row.put("title", meta.getTitle() != null ? meta.getTitle() : meta.getName());
            row.put("category", meta.getCategory());
        }
        row.put("todayCalls", s.today());
        row.put("monthCalls", s.month());
        row.put("totalCalls", s.total());
        row.put("monthFailures", s.monthFailures());
        Double successRate = s.monthSuccessRate();
        if (successRate != null) {
            double successPct = Math.round(successRate * 1000.0) / 10.0;
            row.put("monthSuccessRatePercent", successPct);
            row.put("monthFailureRatePercent", Math.round((100.0 - successPct) * 10.0) / 10.0);
        }
        row.put("monthAvgLatencyMs", s.monthAvgLatencyMs());
        if (meta != null && meta.getUnitPrice() != null && meta.getUnitPrice().compareTo(BigDecimal.ZERO) > 0 && s.month() > 0) {
            row.put("estimatedMonthCostCny", meta.getUnitPrice().multiply(BigDecimal.valueOf(s.month()))
                    .setScale(4, RoundingMode.HALF_UP));
        }
        return row;
    }

    // ==================== 供 diagnose_agent 复用的日志与工具诊断项 ====================

    /**
     * 为 {@link AssistantQueryTools#diagnoseAgent} 补充近期对话日志失败线索与工具失败率检查。
     */
    List<Map<String, Object>> diagnoseLogsAndTools(Agent agent, CurrentActor actor) {
        List<Map<String, Object>> extra = new ArrayList<>();
        if (conversationService != null) {
            try {
                PageResult<AgentConversation> recent = conversationService.listLogs(agent, actor, "7days", null, "updatedAt", "desc", 1, 10);
                List<AgentConversation> records = recent != null && recent.getRecords() != null ? recent.getRecords() : List.of();
                int failedCount = 0;
                int slowCount = 0;
                String sampleError = null;
                for (int i = 0; i < Math.min(records.size(), 5); i++) {
                    Optional<ConversationDetail> detailOpt = safeGetDetail(agent, actor, records.get(i).getId());
                    if (detailOpt.isEmpty()) {
                        continue;
                    }
                    AgentConversationMessage lastAssistant = lastMessageByRole(detailOpt.get().getMessages(), "assistant");
                    if (lastAssistant == null) {
                        continue;
                    }
                    if (isSuspectedFailureReply(lastAssistant.getContent())) {
                        failedCount++;
                        if (sampleError == null) {
                            sampleError = truncate(lastAssistant.getContent(), 80);
                        }
                    }
                    if (lastAssistant.getLatencyMs() != null && lastAssistant.getLatencyMs() > 15_000L) {
                        slowCount++;
                    }
                }
                if (failedCount > 0) {
                    extra.add(check("近期对话日志", "WARN",
                            "最近抽查的对话中发现 " + failedCount + " 条疑似失败/降级回复" + (sampleError != null ? "（示例：" + sampleError + "）" : ""),
                            "使用「智能体 → 日志」排查具体报错，或检查模型通道连通性与额度"));
                } else if (slowCount > 0) {
                    extra.add(check("近期对话日志", "WARN",
                            "最近抽查的对话中有 " + slowCount + " 条响应耗时超过 15 秒",
                            "检查是否开启了深度思考、工具轮数过多，或模型通道响应偏慢"));
                }
            } catch (RuntimeException ignored) {
                // 日志模块异常时不阻断主诊断流程
            }
        }

        if (toolUsageService != null) {
            try {
                Map<String, ToolUsageService.ToolStats> usage = toolUsageService.summary(List.of(agent.getId()));
                List<String> riskyTools = new ArrayList<>();
                for (var entry : usage.entrySet()) {
                    ToolUsageService.ToolStats s = entry.getValue();
                    if (s.month() >= 3 && s.monthFailures() > 0) {
                        double failRate = s.monthFailures() * 100.0 / s.month();
                        if (failRate >= 20.0) {
                            riskyTools.add(entry.getKey() + "（本月 " + s.month() + " 次，失败 " + s.monthFailures()
                                    + " 次，" + Math.round(failRate * 10.0) / 10.0 + "%）");
                        }
                    }
                }
                if (!riskyTools.isEmpty()) {
                    extra.add(check("工具调用失败率", "WARN",
                            "检测到本月高失败率工具：" + String.join("、", riskyTools),
                            "到「构建 → 工具」检查对应 HTTP/MCP 工具的连通性、鉴权密钥与超时配置"));
                }
            } catch (RuntimeException ignored) {
                // 工具统计异常时不阻断主诊断流程
            }
        }
        return extra;
    }

    // ==================== 辅助方法 ====================

    private Optional<ConversationDetail> safeGetDetail(Agent agent, CurrentActor actor, String conversationId) {
        try {
            return conversationService.getDetail(agent, actor, conversationId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static AgentConversationMessage lastMessageByRole(List<AgentConversationMessage> msgs, String role) {
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            AgentConversationMessage m = msgs.get(i);
            if (role.equalsIgnoreCase(m.getRole())) {
                return m;
            }
        }
        return null;
    }

    static boolean isSuspectedFailureReply(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        return FAILURE_CLUE.matcher(content).find();
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return SUPPORTED_RESOURCE_TYPES.contains(normalized) ? normalized : null;
    }

    private static void requireSuperAdmin(CurrentActor actor, String action) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new IllegalStateException(action + "仅超级管理员可用");
        }
    }

    private static void requireModule(Object bean, String moduleName) {
        if (bean == null) {
            throw new IllegalStateException(moduleName + "当前未启用");
        }
    }

    private static String resolveParentRef(JsonNode args, String alias) {
        String parent = filterText(args, "parent");
        if (parent != null) {
            return parent;
        }
        return filterText(args, alias);
    }

    private static String filterText(JsonNode args, String field) {
        String direct = text(args, field);
        if (direct != null) {
            return direct;
        }
        JsonNode filters = args == null ? null : args.get("filters");
        if (filters != null && filters.isObject()) {
            return text(filters, field);
        }
        return null;
    }

    private static Boolean filterBoolean(JsonNode args, String field) {
        Boolean direct = bool(args, field);
        if (direct != null) {
            return direct;
        }
        JsonNode filters = args == null ? null : args.get("filters");
        if (filters != null && filters.isObject()) {
            return bool(filters, field);
        }
        return null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        String s = v.asText("").trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        return null;
    }

    private static int limit(JsonNode args) {
        int val = clampInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (args != null && !args.has("limit") && args.has("filters") && args.get("filters").isObject()) {
            return clampInt(args.get("filters"), "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        }
        return val;
    }

    private static int clampInt(JsonNode args, String field, int defaultVal, int min, int max) {
        JsonNode node = args == null ? null : args.get(field);
        int value = defaultVal;
        if (node != null && node.canConvertToInt()) {
            value = node.asInt();
        } else if (node != null && node.isTextual()) {
            try {
                value = Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                value = defaultVal;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeRange(String raw) {
        if ("today".equalsIgnoreCase(raw)) {
            return "today";
        }
        if ("30days".equalsIgnoreCase(raw)) {
            return "30days";
        }
        return "7days";
    }

    private static String rangeLabel(String range) {
        return switch (range) {
            case "today" -> "今天";
            case "30days" -> "近 30 天";
            default -> "近 7 天";
        };
    }

    private static Double round4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static boolean equalsAnyIgnoreCase(String ref, String... candidates) {
        if (ref == null) {
            return false;
        }
        for (String c : candidates) {
            if (c != null && ref.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
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

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "…" : compact;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME);
    }
}
