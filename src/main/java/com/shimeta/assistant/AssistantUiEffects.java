package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.AssistantAction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 AI 助手的只读查询与已确认写操作映射为结构化的前端界面联动指令（uiEffect），
 * 让用户在当前页面上直观看到助手正在搜索、筛选、写入草稿、修改参数或刷新高亮对应资源。
 */
public final class AssistantUiEffects {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssistantUiEffects() {
    }

    /** 只读/搜索类工具执行成功后生成实时 UI 联动指令（如自动在搜索框输入关键词并筛选） */
    public static Map<String, Object> fromReadTool(String toolName, String argumentsJson, AssistantToolRegistry.Outcome outcome) {
        if (toolName == null || outcome == null || !outcome.ok()) {
            return null;
        }
        JsonNode args = parse(argumentsJson);
        JsonNode res = parse(outcome.content());
        return switch (toolName) {
            case "list_agents" -> {
                String kw = text(args, "keyword");
                String status = text(args, "status");
                String scope = text(args, "scope");
                Map<String, Object> e = base("search", "agents");
                if (kw != null) e.put("keyword", kw);
                if (status != null) e.put("status", status);
                if (scope != null) e.put("scope", scope);
                e.put("label", kw != null
                        ? "AI 助手正在智能体列表中搜索「" + kw + "」"
                        : (status != null ? "AI 助手正在筛选智能体列表" : "AI 助手正在查看智能体列表"));
                yield e;
            }
            case "get_agent_detail" -> {
                String id = text(res, "id");
                String name = text(res, "name");
                if (id == null) yield null;
                Map<String, Object> e = base("focus_agent", "agents");
                e.put("agentId", id);
                if (name != null) e.put("keyword", name);
                e.put("label", name != null ? "AI 助手正在定位智能体「" + name + "」" : "AI 助手正在查看智能体详情");
                yield e;
            }
            case "get_agent_logs" -> {
                String id = text(res, "agentId");
                Map<String, Object> e = base("focus_agent", "agent_debug");
                if (id != null) e.put("agentId", id);
                e.put("openTab", "logs");
                e.put("label", "AI 助手正在查看智能体调用日志");
                yield e;
            }
            case "list_knowledge_bases" -> {
                String kw = text(args, "keyword");
                Map<String, Object> e = base("search", "knowledge");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在知识库中搜索「" + kw + "」" : "AI 助手正在查看知识库列表");
                yield e;
            }
            case "list_knowledge_documents" -> {
                String kbId = text(res, "knowledgeBaseId");
                String kw = text(args, "keyword");
                Map<String, Object> e = base("focus_kb_tab", "knowledge");
                if (kbId != null) e.put("kbId", kbId);
                e.put("subTab", "documents");
                if (kw != null) e.put("keyword", kw);
                e.put("label", "AI 助手正在查看知识库文档");
                yield e;
            }
            case "list_templates" -> {
                String kw = text(args, "keyword");
                Map<String, Object> e = base("search", "templates");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在场景模板中搜索「" + kw + "」" : "AI 助手正在查看场景模板");
                yield e;
            }
            case "list_skills" -> {
                String kw = text(args, "keyword");
                Map<String, Object> e = base("search", "skills");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在技能库中搜索「" + kw + "」" : "AI 助手正在查看技能库");
                yield e;
            }
            case "list_mcp_servers" -> {
                Map<String, Object> e = base("focus", "tools");
                e.put("subTab", "mcp");
                e.put("label", "AI 助手正在查看 MCP 服务列表");
                yield e;
            }
            case "get_gateway_status" -> {
                Map<String, Object> e = base("focus", "gateway");
                e.put("label", "AI 助手正在检查模型网关状态");
                yield e;
            }
            case "navigate_to_page" -> {
                String url = text(res, "url");
                String page = text(res, "page");
                if (url == null || page == null) yield null;
                Map<String, Object> e = base("navigate", page);
                e.put("url", url);
                String dashboardTab = text(res, "dashboardTab");
                if (dashboardTab != null) e.put("dashboardTab", dashboardTab);
                String agentId = text(res, "agentId");
                if (agentId != null) e.put("agentId", agentId);
                String kbId = text(res, "kbId");
                if (kbId != null) e.put("kbId", kbId);
                String subTab = text(res, "subTab");
                if (subTab != null) e.put("subTab", subTab);
                String kw = text(res, "keyword");
                if (kw != null) e.put("keyword", kw);
                String title = text(res, "title");
                e.put("label", title != null ? "AI 助手正在打开「" + title + "」" : "AI 助手正在切换页面");
                yield e;
            }
            case "list_resources" -> fromListResources(args, res);
            default -> null;
        };
    }

    private static Map<String, Object> fromListResources(JsonNode args, JsonNode res) {
        String type = text(args, "resourceType");
        if (type == null) {
            return null;
        }
        String kw = text(args, "keyword");
        String lower = type.toLowerCase();
        return switch (lower) {
            case "faq", "faqs" -> {
                Map<String, Object> e = base("focus_kb_tab", "knowledge");
                String kbId = text(res, "knowledgeBaseId");
                if (kbId != null) e.put("kbId", kbId);
                e.put("subTab", "faqs");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在搜索 FAQ「" + kw + "」" : "AI 助手正在查看知识库 FAQ");
                yield e;
            }
            case "http_tool", "tool", "tools" -> {
                Map<String, Object> e = base("search", "tools");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在搜索工具「" + kw + "」" : "AI 助手正在查看工具列表");
                yield e;
            }
            case "user", "users" -> {
                Map<String, Object> e = base("search", "users");
                if (kw != null) e.put("keyword", kw);
                e.put("label", kw != null ? "AI 助手正在搜索用户「" + kw + "」" : "AI 助手正在查看用户列表");
                yield e;
            }
            case "api_key", "terminal", "audit_log", "alert_rule" -> {
                Map<String, Object> e = base("focus", "security");
                e.put("subTab", lower);
                e.put("label", "AI 助手正在查看开放与安全配置");
                yield e;
            }
            default -> null;
        };
    }

    /** 写操作卡片确认执行成功后，生成前端实时演播与刷新指令 */
    public static Map<String, Object> fromExecutedAction(AssistantAction action, Map<String, Object> result) {
        if (action == null || !AssistantAction.EXECUTED.equals(action.getStatus())) {
            return null;
        }
        String tool = action.getToolName();
        JsonNode payload = parse(action.getPayload());
        String resId = action.getResourceId();
        return switch (tool) {
            case "update_agent_prompt", "rollback_agent_prompt" -> {
                Map<String, Object> e = base("agent_prompt_updated", "agent_debug");
                String agentId = firstNonBlank(resId, text(payload, "agentId"));
                if (agentId != null) e.put("agentId", agentId);
                String newPrompt = text(payload, "systemPrompt");
                if (newPrompt != null) e.put("systemPrompt", newPrompt);
                e.put("label", "rollback_agent_prompt".equals(tool)
                        ? "AI 助手已回滚系统提示词并同步界面"
                        : "AI 助手正在更新系统提示词并保存上线");
                yield e;
            }
            case "update_agent_settings" -> {
                Map<String, Object> e = base("agent_settings_updated", "agent_debug");
                String agentId = firstNonBlank(resId, text(payload, "agentId"));
                if (agentId != null) e.put("agentId", agentId);
                Map<String, Object> fd = extractFields(payload,
                        "temperature", "topP", "maxTokens", "memoryTurns", "memoryCompression",
                        "thinkingMode", "knowledgeMode", "primaryProviderId", "modelName");
                if (!fd.isEmpty()) e.put("formData", fd);
                boolean toolsChanged = payload != null && payload.hasNonNull("toolsConfig");
                boolean skillsChanged = payload != null && payload.hasNonNull("skillIds");
                if (toolsChanged) e.put("toolsChanged", true);
                if (skillsChanged) e.put("skillsChanged", true);
                e.put("label", toolsChanged
                        ? "AI 助手正在同步智能体工具开关…"
                        : (skillsChanged ? "AI 助手正在同步智能体技能关联…" : "AI 助手正在打开模型设置同步参数…"));
                yield e;
            }
            case "manage_agent_variable" -> {
                Map<String, Object> e = base("agent_variable_mutated", "agent_debug");
                String agentId = firstNonBlank(resId, text(payload, "agentId"));
                if (agentId != null) e.put("agentId", agentId);
                String op = firstNonBlank(text(payload, "operation"), "add");
                e.put("operation", op);
                if (payload != null && payload.has("variable") && payload.get("variable").isObject()) {
                    e.put("variable", MAPPER.convertValue(payload.get("variable"), Map.class));
                }
                e.put("label", "delete".equals(op)
                        ? "AI 助手正在移除自定义变量…"
                        : "AI 助手正在打开变量弹窗填入并保存…");
                yield e;
            }
            case "bind_knowledge_base" -> {
                Map<String, Object> e = base("agent_kb_updated", "agent_debug");
                String agentId = firstNonBlank(resId, text(payload, "agentId"));
                if (agentId != null) e.put("agentId", agentId);
                String kbId = firstNonBlank(text(payload, "knowledgeBaseId"), text(payload, "kbId"));
                if (kbId != null) e.put("kbId", kbId);
                boolean unbind = payload != null && ("unbind".equalsIgnoreCase(payload.path("action").asText(""))
                        || payload.path("unbind").asBoolean(false));
                e.put("operation", unbind ? "unbind" : "bind");
                e.put("label", unbind ? "AI 助手正在取消关联知识库…" : "AI 助手正在打开关联知识库弹窗并绑定…");
                yield e;
            }
            case "set_agent_status" -> {
                Map<String, Object> e = base("agent_status_updated", "agents");
                String agentId = firstNonBlank(resId, text(payload, "agentId"));
                if (agentId != null) e.put("agentId", agentId);
                String status = text(payload, "status");
                if (status != null) e.put("status", status);
                e.put("label", "AI 助手正在更新智能体运行状态…");
                yield e;
            }
            case "create_agent" -> {
                Map<String, Object> e = base("resource_created", "agents");
                if (resId != null) e.put("resourceId", resId);
                String name = text(payload, "name");
                if (name != null) e.put("name", name);
                Map<String, Object> fd = extractFields(payload,
                        "name", "category", "temperature", "status", "systemPrompt", "description", "tags");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", name != null ? "AI 助手正在打开弹窗创建智能体「" + name + "」…" : "AI 助手正在打开弹窗新建智能体…");
                yield e;
            }
            case "create_knowledge_base" -> {
                Map<String, Object> e = base("resource_created", "knowledge");
                if (resId != null) {
                    e.put("resourceId", resId);
                    e.put("kbId", resId);
                }
                Map<String, Object> fd = extractFields(payload, "name", "description", "provider");
                if (!fd.isEmpty()) e.put("formData", fd);
                String name = text(payload, "name");
                e.put("label", name != null ? "AI 助手正在打开弹窗新建知识库「" + name + "」…" : "AI 助手正在打开弹窗新建知识库…");
                yield e;
            }
            case "update_kb_settings" -> {
                Map<String, Object> e = base("kb_settings_updated", "knowledge");
                String kbId = firstNonBlank(resId, text(payload, "kbId"), text(payload, "knowledgeBaseId"));
                if (kbId != null) {
                    e.put("resourceId", kbId);
                    e.put("kbId", kbId);
                }
                Map<String, Object> fd = extractFields(payload,
                        "searchMethod", "topK", "scoreThreshold", "vectorWeight", "keywordWeight", "rerankEnabled", "rerankMode");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "AI 助手正在打开知识库配置弹窗同步检索参数…");
                yield e;
            }
            case "rebuild_kb_index" -> {
                Map<String, Object> e = base("kb_rebuild", "knowledge");
                String kbId = firstNonBlank(resId, text(payload, "kbId"), text(payload, "knowledgeBaseId"));
                if (kbId != null) {
                    e.put("resourceId", kbId);
                    e.put("kbId", kbId);
                }
                e.put("subTab", "documents");
                e.put("label", "AI 助手已启动知识库索引重建并刷新进度");
                yield e;
            }
            case "run_rag_eval" -> {
                Map<String, Object> e = base("kb_eval", "knowledge");
                String kbId = firstNonBlank(resId, text(payload, "kbId"), text(payload, "knowledgeBaseId"));
                if (kbId != null) {
                    e.put("resourceId", kbId);
                    e.put("kbId", kbId);
                }
                e.put("subTab", "evaluation");
                e.put("label", "AI 助手已发起知识库检索评测并切换到评测页");
                yield e;
            }
            case "add_faq", "update_faq" -> {
                Map<String, Object> e = base("faq_mutated", "knowledge");
                String kbId = firstNonBlank(text(payload, "kbId"), text(payload, "knowledgeBaseId"));
                if (kbId != null) e.put("kbId", kbId);
                if (resId != null) e.put("resourceId", resId);
                String faqId = firstNonBlank(text(payload, "faqId"), resId);
                if (faqId != null) e.put("faqId", faqId);
                e.put("operation", "add_faq".equals(tool) ? "add" : "update");
                e.put("subTab", "faqs");
                Map<String, Object> fd = extractFields(payload, "question", "answer", "category", "imageUrls", "videoUrls");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "add_faq".equals(tool) ? "AI 助手正在打开 FAQ 弹窗填入并保存…" : "AI 助手正在打开 FAQ 弹窗更新并保存…");
                yield e;
            }
            case "save_agent_as_template" -> {
                Map<String, Object> e = base("resource_created", "templates");
                if (resId != null) e.put("resourceId", resId);
                Map<String, Object> fd = extractFields(payload, "name", "category", "description", "systemPrompt", "temperature", "tags");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "AI 助手正在打开模板弹窗填入并保存…");
                yield e;
            }
            case "create_skill" -> {
                Map<String, Object> e = base("resource_created", "skills");
                if (resId != null) e.put("resourceId", resId);
                Map<String, Object> fd = extractFields(payload, "name", "description", "content");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "AI 助手正在打开技能弹窗填入并保存…");
                yield e;
            }
            case "create_http_tool" -> {
                Map<String, Object> e = base("resource_created", "tools");
                if (resId != null) e.put("resourceId", resId);
                Map<String, Object> fd = extractFields(payload, "name", "title", "description", "url", "method");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "AI 助手正在打开工具弹窗填入并保存…");
                yield e;
            }
            case "register_mcp_server", "refresh_mcp_server" -> {
                Map<String, Object> e = base("register_mcp_server".equals(tool) ? "resource_created" : "resource_mutated", "tools");
                if (resId != null) e.put("resourceId", resId);
                e.put("subTab", "mcp");
                e.put("operation", "register_mcp_server".equals(tool) ? "create" : "refresh");
                if ("register_mcp_server".equals(tool)) {
                    Map<String, Object> fd = extractFields(payload, "name", "url", "transport", "description");
                    if (!fd.isEmpty()) e.put("formData", fd);
                }
                e.put("label", "register_mcp_server".equals(tool)
                        ? "AI 助手正在打开 MCP 服务弹窗填入并保存…"
                        : "AI 助手正在刷新 MCP 工具列表…");
                yield e;
            }
            case "manage_gateway_channel" -> {
                Map<String, Object> e = base("resource_mutated", "gateway");
                if (resId != null) e.put("resourceId", resId);
                String op = text(payload, "operation");
                if (op != null) e.put("operation", op);
                String name = text(payload, "name");
                if (name != null) e.put("name", name);
                e.put("label", "AI 助手已更新模型网关配置并刷新界面");
                yield e;
            }
            case "manage_user" -> {
                String op = firstNonBlank(text(payload, "operation"), "update");
                Map<String, Object> e = base("create".equalsIgnoreCase(op) ? "resource_created" : "resource_mutated", "users");
                if (resId != null) e.put("resourceId", resId);
                e.put("operation", op.toLowerCase());
                // 严禁把密码写入 uiEffect，仅传递非敏感展示字段用于新建弹窗演播
                Map<String, Object> fd = extractFields(payload, "username", "nickname", "role");
                if (!fd.isEmpty()) e.put("formData", fd);
                e.put("label", "create".equalsIgnoreCase(op)
                        ? "AI 助手正在打开新建用户弹窗填入并保存…"
                        : "AI 助手已更新用户信息并刷新列表");
                yield e;
            }
            case "create_api_key", "revoke_api_key", "update_guardrail_policy" -> {
                Map<String, Object> e = base("create_api_key".equals(tool) ? "resource_created" : "resource_mutated", "security");
                if (resId != null) e.put("resourceId", resId);
                e.put("subTab", "update_guardrail_policy".equals(tool) ? "policy" : "api_key");
                e.put("operation", "create_api_key".equals(tool) ? "create" : ("revoke_api_key".equals(tool) ? "revoke" : "policy"));
                if ("create_api_key".equals(tool)) {
                    Map<String, Object> fd = extractFields(payload, "name", "agentId");
                    if (!fd.isEmpty()) e.put("formData", fd);
                }
                e.put("label", "create_api_key".equals(tool)
                        ? "AI 助手正在打开签发凭证弹窗填入并创建…"
                        : ("update_guardrail_policy".equals(tool) ? "AI 助手正在同步护栏策略配置…" : "AI 助手正在刷新开放凭证列表…"));
                yield e;
            }
            case "disable_share_link" -> {
                Map<String, Object> e = base("agent_share_updated", "agent_debug");
                String agentId = text(payload, "agentId");
                if (agentId != null) e.put("agentId", agentId);
                e.put("label", "AI 助手正在切换到访问入口并更新分享链接状态…");
                yield e;
            }
            case "delete_resource" -> {
                String target = mapDeleteTarget(firstNonBlank(text(payload, "resourceType"), action.getResourceType()));
                Map<String, Object> e = base("resource_deleted", target);
                if (resId != null) e.put("resourceId", resId);
                String kbId = firstNonBlank(text(payload, "kbId"), text(payload, "knowledgeBaseId"));
                if (kbId != null) e.put("kbId", kbId);
                e.put("label", "AI 助手已删除资源并刷新列表");
                yield e;
            }
            default -> null;
        };
    }

    private static String mapDeleteTarget(String resourceType) {
        if (resourceType == null) return "agents";
        return switch (resourceType.toLowerCase()) {
            case "knowledge_base", "document", "faq" -> "knowledge";
            case "template", "agent_template" -> "templates";
            case "skill" -> "skills";
            case "http_tool", "mcp_server", "platform_tool" -> "tools";
            case "user" -> "users";
            default -> "agents";
        };
    }

    private static Map<String, Object> base(String kind, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("target", target);
        return m;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String s = node.get(field).asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static Map<String, Object> extractFields(JsonNode node, String... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                out.put(f, MAPPER.convertValue(node.get(f), Object.class));
            }
        }
        return out;
    }
}
