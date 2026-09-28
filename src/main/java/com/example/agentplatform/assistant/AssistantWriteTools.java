package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AgentTemplate;
import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeFaq;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.AgentTemplateService;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 平台 AI 助手的写工具（P2）。
 *
 * <p>写工具被模型调用时<b>不修改任何数据</b>：只校验参数与权限，生成一条 PENDING 的待确认操作并在对话中渲染操作卡片；
 * 用户确认后由 {@link AssistantActionService} 调用 {@link #execute} 执行，执行时只使用保存的参数，并重新以当前用户身份走各 Service 的权限校验。
 * 不提供删除资源、停用用户、修改角色、修改网关密钥等不可逆或高危操作。
 */
@Component
public class AssistantWriteTools {

    static final String CREATE_AGENT = "create_agent";
    static final String CREATE_KNOWLEDGE_BASE = "create_knowledge_base";
    static final String ADD_FAQ = "add_faq";
    static final String BIND_KNOWLEDGE_BASE = "bind_knowledge_base";
    static final String UPDATE_AGENT_PROMPT = "update_agent_prompt";
    static final String SET_AGENT_STATUS = "set_agent_status";

    static final int ACTION_TTL_MINUTES = 10;
    static final int NAME_MAX = 50;
    static final int DESCRIPTION_MAX = 300;
    static final int PROMPT_MAX = 8000;
    static final int QUESTION_MAX = 300;
    static final int ANSWER_MAX = 4000;
    static final List<String> AGENT_CATEGORIES = List.of("代码研发", "运维架构", "产品策划", "知识库客服", "数据分析", "内容创作", "通用智能");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");
    private static final Set<String> SETTABLE_STATUSES = Set.of("RUNNING", "DISABLED");

    private final AssistantQueryTools queryTools;
    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentTemplateService templateService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final EmbeddingConfigService embeddingConfigService;
    private final ResourceAuthorizationService authorizationService;
    private final AssistantActionRepository actionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssistantWriteTools(AssistantQueryTools queryTools,
                               AgentService agentService,
                               AgentRepository agentRepository,
                               AgentTemplateService templateService,
                               KnowledgeBaseService knowledgeBaseService,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               EmbeddingConfigService embeddingConfigService,
                               ResourceAuthorizationService authorizationService,
                               AssistantActionRepository actionRepository) {
        this.queryTools = queryTools;
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.templateService = templateService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.embeddingConfigService = embeddingConfigService;
        this.authorizationService = authorizationService;
        this.actionRepository = actionRepository;
    }

    public List<AssistantTool> tools() {
        return List.of(
                AssistantTool.write(CREATE_AGENT, "生成创建智能体操作",
                        "创建一个新的智能体（生成待确认操作卡片，用户确认后才创建）。可以只给出名称与完整的系统提示词，也可以指定 templateId 基于场景模板创建。"
                                + "系统提示词要完整、专业，写清角色、职责、回答规范与边界。",
                        schema(Map.of(
                                "name", prop("string", "智能体名称，不超过 50 字"),
                                "systemPrompt", prop("string", "完整的系统提示词；基于模板创建时可省略，默认使用模板的提示词"),
                                "category", Map.of("type", "string", "enum", AGENT_CATEGORIES, "description", "分类，默认 通用智能"),
                                "description", prop("string", "一句话描述，可选"),
                                "code", prop("string", "唯一业务编码（字母开头，字母数字下划线），可选，不填自动生成"),
                                "templateId", prop("string", "场景模板 ID（来自 list_templates），可选"),
                                "knowledgeBases", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "要绑定的知识库名称或 ID 列表，可选"),
                                "temperature", prop("number", "采样温度 0~2，可选")), "name"),
                        this::draftCreateAgent),
                AssistantTool.write(CREATE_KNOWLEDGE_BASE, "生成创建知识库操作",
                        "创建一个平台内置引擎的知识库（生成待确认操作卡片，用户确认后才创建）。",
                        schema(Map.of(
                                "name", prop("string", "知识库名称，不超过 50 字"),
                                "description", prop("string", "知识库用途描述，可选")), "name"),
                        this::draftCreateKnowledgeBase),
                AssistantTool.write(ADD_FAQ, "生成添加 FAQ 操作",
                        "向知识库添加一条问答对（生成待确认操作卡片）。一次调用只添加一条。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "question", prop("string", "问题"),
                                "answer", prop("string", "答案"),
                                "category", prop("string", "FAQ 分类，可选")), "knowledgeBase", "question", "answer"),
                        this::draftAddFaq),
                AssistantTool.write(BIND_KNOWLEDGE_BASE, "生成绑定知识库操作",
                        "给智能体绑定或解绑一个知识库（生成待确认操作卡片，卡片展示修改前后）。",
                        schema(Map.of(
                                "agent", prop("string", "智能体名称、编码或 ID"),
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "operation", Map.of("type", "string", "enum", List.of("bind", "unbind"),
                                        "description", "bind 绑定（默认）/ unbind 解绑")), "agent", "knowledgeBase"),
                        this::draftBindKnowledgeBase),
                AssistantTool.write(UPDATE_AGENT_PROMPT, "生成修改提示词操作",
                        "修改智能体的系统提示词（生成待确认操作卡片，卡片展示修改前后对比）。先用 get_agent_detail 了解现有配置，"
                                + "传入修改后的完整提示词，而不是只传修改的片段。",
                        schema(Map.of(
                                "agent", prop("string", "智能体名称、编码或 ID"),
                                "systemPrompt", prop("string", "修改后的完整系统提示词")), "agent", "systemPrompt"),
                        this::draftUpdateAgentPrompt),
                AssistantTool.write(SET_AGENT_STATUS, "生成启停智能体操作",
                        "启用或停用智能体（生成待确认操作卡片）。",
                        schema(Map.of(
                                "agent", prop("string", "智能体名称、编码或 ID"),
                                "status", Map.of("type", "string", "enum", List.of("RUNNING", "DISABLED"),
                                        "description", "RUNNING 启用 / DISABLED 停用")), "agent", "status"),
                        this::draftSetAgentStatus)
        );
    }

    // ==================== 生成待确认操作 ====================

    Map<String, Object> draftCreateAgent(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        String name = required(args, "name", NAME_MAX, "智能体名称");
        AgentTemplate template = null;
        String templateId = text(args, "templateId");
        if (templateId != null) {
            template = templateService.getById(templateId, actor)
                    .orElseThrow(() -> new IllegalArgumentException("场景模板不存在：" + templateId));
        }
        String prompt = text(args, "systemPrompt");
        if (prompt == null && template != null) {
            prompt = template.getSystemPrompt();
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请提供完整的系统提示词，或指定场景模板");
        }
        checkMax(prompt, PROMPT_MAX, "系统提示词");

        String category = text(args, "category");
        if (category == null) {
            category = template != null && template.getCategory() != null ? template.getCategory() : "通用智能";
        } else if (!AGENT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("分类只能是：" + String.join("、", AGENT_CATEGORIES));
        }
        String description = text(args, "description");
        if (description == null && template != null) {
            description = template.getDescription();
        }
        checkMax(description, DESCRIPTION_MAX, "描述");

        String code = text(args, "code");
        boolean autoCode = code == null;
        if (autoCode) {
            code = generateCode();
        } else {
            if (!CODE_PATTERN.matcher(code).matches()) {
                throw new IllegalArgumentException("编码需以字母开头，只含字母、数字、下划线或短横线，长度 3~64");
            }
            if (agentRepository.existsByCode(code)) {
                throw new IllegalArgumentException("编码「" + code + "」已被占用，请换一个或不填自动生成");
            }
        }

        Double temperature = number(args, "temperature");
        if (temperature == null && template != null) {
            temperature = template.getTemperature();
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("温度需在 0~2 之间");
        }

        List<KnowledgeBase> kbs = new ArrayList<>();
        for (String ref : textList(args, "knowledgeBases")) {
            KnowledgeBase kb = queryTools.resolveKnowledgeBase(ref, actor);
            if (!authorizationService.canUseKnowledgeBase(actor, kb)) {
                throw new IllegalArgumentException("你没有知识库「" + kb.getName() + "」的使用权限，不能绑定");
            }
            if (kbs.stream().noneMatch(existing -> existing.getId().equals(kb.getId()))) {
                kbs.add(kb);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("code", code);
        payload.put("autoCode", autoCode);
        payload.put("category", category);
        payload.put("description", description);
        payload.put("systemPrompt", prompt);
        payload.put("temperature", temperature);
        payload.put("knowledgeBaseIds", kbs.stream().map(KnowledgeBase::getId).toList());
        payload.put("templateId", template != null ? template.getId() : null);
        if (template != null) {
            payload.put("modelName", template.getModelName());
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("名称", name));
        fields.add(field("编码", code + (autoCode ? "（自动生成）" : "")));
        fields.add(field("分类", category));
        fields.add(field("模型", template != null && template.getModelName() != null && !template.getModelName().isBlank()
                ? template.getModelName() : "跟随网关默认通道"));
        if (description != null) {
            fields.add(field("描述", description));
        }
        fields.add(longField("系统提示词", prompt));
        fields.add(field("绑定知识库", kbs.isEmpty() ? "无" : String.join("、", kbs.stream().map(KnowledgeBase::getName).toList())));
        if (template != null) {
            fields.add(field("来源模板", template.getName()));
        }
        if (temperature != null) {
            fields.add(field("温度", String.valueOf(temperature)));
        }
        return pending(ctx, CREATE_AGENT, "W1", "创建智能体：" + name, payload, preview(fields, null, null), "AGENT");
    }

    Map<String, Object> draftCreateKnowledgeBase(JsonNode args, ToolContext ctx) {
        String name = required(args, "name", NAME_MAX, "知识库名称");
        String description = text(args, "description");
        checkMax(description, DESCRIPTION_MAX, "描述");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", description);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("名称", name));
        if (description != null) {
            fields.add(field("描述", description));
        }
        fields.add(field("引擎", "平台内置引擎"));
        String note = embeddingConfigService.getActiveConfig().isEmpty()
                ? "当前未激活向量模型：创建后请先在「模型网关 → 向量模型」中配置并激活，再上传文档，否则语义检索效果很差。"
                : null;
        return pending(ctx, CREATE_KNOWLEDGE_BASE, "W1", "创建知识库：" + name, payload, preview(fields, null, note), "KNOWLEDGE_BASE");
    }

    Map<String, Object> draftAddFaq(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限，不能添加 FAQ");
        }
        String question = required(args, "question", QUESTION_MAX, "问题");
        String answer = required(args, "answer", ANSWER_MAX, "答案");
        String category = text(args, "category");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeBaseId", kb.getId());
        payload.put("question", question);
        payload.put("answer", answer);
        payload.put("category", category);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("知识库", kb.getName()));
        fields.add(field("问题", question));
        fields.add(longField("答案", answer));
        if (category != null) {
            fields.add(field("分类", category));
        }
        return pending(ctx, ADD_FAQ, "W1", "添加 FAQ 到「" + kb.getName() + "」", payload, preview(fields, null, null), "KNOWLEDGE_BASE");
    }

    Map<String, Object> draftBindKnowledgeBase(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageable(match.agent(), actor);
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        boolean unbind = "unbind".equalsIgnoreCase(text(args, "operation"));
        List<String> before = currentKnowledgeBaseIds(agent);
        if (unbind && !before.contains(kb.getId())) {
            throw new IllegalArgumentException("智能体「" + agent.getName() + "」没有绑定知识库「" + kb.getName() + "」");
        }
        if (!unbind) {
            if (before.contains(kb.getId())) {
                throw new IllegalArgumentException("智能体「" + agent.getName() + "」已经绑定了知识库「" + kb.getName() + "」");
            }
            if (!authorizationService.canUseKnowledgeBase(actor, kb)) {
                throw new IllegalArgumentException("你没有知识库「" + kb.getName() + "」的使用权限，不能绑定");
            }
        }
        List<String> after = new ArrayList<>(before);
        if (unbind) {
            after.remove(kb.getId());
        } else {
            after.add(kb.getId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        payload.put("knowledgeBaseId", kb.getId());
        payload.put("operation", unbind ? "unbind" : "bind");
        List<Map<String, Object>> fields = List.of(
                field("智能体", agent.getName()),
                field("操作", unbind ? "解绑知识库" : "绑定知识库"),
                field("知识库", kb.getName()));
        Map<String, Object> diff = diff("绑定的知识库", knowledgeBaseNames(before, actor), knowledgeBaseNames(after, actor));
        String title = (unbind ? "解绑知识库：" : "绑定知识库：") + agent.getName() + (unbind ? " ✕ " : " ← ") + kb.getName();
        return pending(ctx, BIND_KNOWLEDGE_BASE, "W2", title, payload, preview(fields, diff, null), "AGENT");
    }

    Map<String, Object> draftUpdateAgentPrompt(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageable(match.agent(), actor);
        String prompt = required(args, "systemPrompt", PROMPT_MAX, "系统提示词");
        String current = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt();
        if (prompt.equals(current)) {
            throw new IllegalArgumentException("新提示词与当前提示词完全相同，无需修改");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        payload.put("systemPrompt", prompt);
        // 基线：确认执行时若提示词已被别人修改，拒绝执行，避免覆盖
        payload.put("baselineSha256", PlatformDocsService.sha256(current.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<Map<String, Object>> fields = List.of(field("智能体", agent.getName()));
        return pending(ctx, UPDATE_AGENT_PROMPT, "W2", "修改提示词：" + agent.getName(), payload,
                preview(fields, diff("系统提示词", current, prompt), null), "AGENT");
    }

    Map<String, Object> draftSetAgentStatus(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageable(match.agent(), actor);
        String status = text(args, "status");
        if (status == null || !SETTABLE_STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("状态只能是 RUNNING（启用）或 DISABLED（停用）");
        }
        AgentStatus target = AgentStatus.valueOf(status.toUpperCase(Locale.ROOT));
        if (agent.getStatus() == target) {
            throw new IllegalArgumentException("智能体「" + agent.getName() + "」已经是「" + target.getLabel() + "」状态");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        payload.put("status", target.name());
        String before = agent.getStatus() != null ? agent.getStatus().getLabel() : "运行中";
        String note = target == AgentStatus.DISABLED
                ? "停用后该智能体将无法被调用，包括通过开放 API 的请求。"
                : null;
        return pending(ctx, SET_AGENT_STATUS, "W2", (target == AgentStatus.DISABLED ? "停用智能体：" : "启用智能体：") + agent.getName(),
                payload, preview(List.of(field("智能体", agent.getName())), diff("状态", before, target.getLabel()), note), "AGENT");
    }

    private Map<String, Object> pending(ToolContext ctx, String toolName, String risk, String title,
                                        Map<String, Object> payload, Map<String, Object> preview, String resourceType) {
        AssistantAction action = new AssistantAction();
        action.setConversationId(ctx.conversationId());
        action.setUserId(ctx.actor().getUserId());
        action.setToolName(toolName);
        action.setRiskLevel(risk);
        action.setTitle(title.length() > 200 ? title.substring(0, 199) + "…" : title);
        action.setPayload(toJson(payload));
        action.setPreview(toJson(preview));
        action.setStatus(AssistantAction.PENDING);
        action.setResourceType(resourceType);
        action.setExpiresAt(LocalDateTime.now().plusMinutes(ACTION_TTL_MINUTES));
        action = actionRepository.save(action);
        ctx.createdActions().add(action);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("pendingActionId", action.getId());
        result.put("title", action.getTitle());
        result.put("message", "已生成待确认操作卡片。请告诉用户在卡片中核对内容并点击确认，" + ACTION_TTL_MINUTES
                + " 分钟内有效；用户确认前不要声称操作已经完成。");
        return result;
    }

    // ==================== 确认后执行 ====================

    /** 执行结果：资源类型与 ID、给用户看的说明，以及跳转链接（可为空）。 */
    public record ExecutionResult(String resourceType, String resourceId, String message, String linkLabel, String linkUrl) {
    }

    /**
     * 用户确认后执行：只使用保存的参数，并以当前用户身份调用各 Service（Service 内部会重新做权限校验）。
     * 参数或权限不再满足时抛出业务异常，由调用方记为失败。
     */
    public ExecutionResult execute(String toolName, String payloadJson, CurrentActor actor) {
        JsonNode p;
        try {
            p = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("操作参数已损坏，无法执行");
        }
        return switch (toolName) {
            case CREATE_AGENT -> executeCreateAgent(p, actor);
            case CREATE_KNOWLEDGE_BASE -> executeCreateKnowledgeBase(p, actor);
            case ADD_FAQ -> executeAddFaq(p, actor);
            case BIND_KNOWLEDGE_BASE -> executeBindKnowledgeBase(p, actor);
            case UPDATE_AGENT_PROMPT -> executeUpdateAgentPrompt(p, actor);
            case SET_AGENT_STATUS -> executeSetAgentStatus(p, actor);
            default -> throw new IllegalStateException("不支持的操作：" + toolName);
        };
    }

    private ExecutionResult executeCreateAgent(JsonNode p, CurrentActor actor) {
        String code = p.path("code").asText();
        if (agentRepository.existsByCode(code)) {
            if (!p.path("autoCode").asBoolean(false)) {
                throw new IllegalStateException("编码「" + code + "」在生成卡片后已被占用，请重新生成");
            }
            code = generateCode();
        }
        Agent agent = new Agent();
        agent.setName(p.path("name").asText());
        agent.setCode(code);
        agent.setCategory(p.path("category").asText("通用智能"));
        agent.setDescription(textOrNull(p, "description"));
        agent.setSystemPrompt(p.path("systemPrompt").asText());
        agent.setModelName(textOrNull(p, "modelName"));
        agent.setAvatar("🤖");
        if (p.hasNonNull("temperature")) {
            agent.setTemperature(p.get("temperature").asDouble());
        }
        List<String> kbIds = new ArrayList<>();
        p.path("knowledgeBaseIds").forEach(node -> kbIds.add(node.asText()));
        agent.setKnowledgeBaseIds(kbIds);
        Agent created = agentService.create(agent, actor);
        return new ExecutionResult("AGENT", created.getId(), "已创建智能体「" + created.getName() + "」",
                "去调试", "/debug/" + created.getId());
    }

    private ExecutionResult executeCreateKnowledgeBase(JsonNode p, CurrentActor actor) {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName(p.path("name").asText());
        req.setDescription(textOrNull(p, "description"));
        req.setProvider("SPRING_AI");
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(req, actor);
        return new ExecutionResult("KNOWLEDGE_BASE", kb.getId(), "已创建知识库「" + kb.getName() + "」，可以去上传文档或添加 FAQ",
                "查看知识库", "/dashboard?tab=knowledge");
    }

    private ExecutionResult executeAddFaq(JsonNode p, CurrentActor actor) {
        CreateFaqRequest req = new CreateFaqRequest();
        req.setQuestion(p.path("question").asText());
        req.setAnswer(p.path("answer").asText());
        if (p.hasNonNull("category")) {
            req.setCategory(p.get("category").asText());
        }
        String kbId = p.path("knowledgeBaseId").asText();
        KnowledgeFaq faq = knowledgeBaseService.createFaq(kbId, req, actor);
        return new ExecutionResult("KNOWLEDGE_BASE", kbId, "已添加 FAQ（ID " + faq.getId() + "）",
                "查看知识库", "/dashboard?tab=knowledge");
    }

    private ExecutionResult executeBindKnowledgeBase(JsonNode p, CurrentActor actor) {
        Agent agent = loadManageable(p.path("agentId").asText(), actor);
        String kbId = p.path("knowledgeBaseId").asText();
        boolean unbind = "unbind".equals(p.path("operation").asText());
        List<String> ids = currentKnowledgeBaseIds(agent);
        if (unbind) {
            ids.remove(kbId);
        } else if (!ids.contains(kbId)) {
            ids.add(kbId);
        }
        Agent patch = patchFrom(agent);
        patch.setKnowledgeBaseIds(ids);
        agentService.update(agent.getId(), patch, actor);
        String kbName = knowledgeBaseRepository.findById(kbId).map(KnowledgeBase::getName).orElse(kbId);
        return new ExecutionResult("AGENT", agent.getId(),
                (unbind ? "已为「" + agent.getName() + "」解绑知识库「" : "已为「" + agent.getName() + "」绑定知识库「") + kbName + "」",
                "去调试", "/debug/" + agent.getId());
    }

    private ExecutionResult executeUpdateAgentPrompt(JsonNode p, CurrentActor actor) {
        Agent agent = loadManageable(p.path("agentId").asText(), actor);
        String current = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt();
        String baseline = p.path("baselineSha256").asText();
        if (!baseline.equalsIgnoreCase(PlatformDocsService.sha256(current.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("生成卡片后该智能体的提示词已被修改，为避免覆盖，请让助手重新生成修改");
        }
        Agent patch = patchFrom(agent);
        patch.setSystemPrompt(p.path("systemPrompt").asText());
        agentService.update(agent.getId(), patch, actor);
        return new ExecutionResult("AGENT", agent.getId(), "已更新「" + agent.getName() + "」的系统提示词",
                "去调试", "/debug/" + agent.getId());
    }

    private ExecutionResult executeSetAgentStatus(JsonNode p, CurrentActor actor) {
        String agentId = p.path("agentId").asText();
        AgentStatus status = AgentStatus.valueOf(p.path("status").asText());
        Agent updated = agentService.updateStatus(agentId, status, actor);
        return new ExecutionResult("AGENT", agentId, "「" + updated.getName() + "」已" + (status == AgentStatus.DISABLED ? "停用" : "启用"),
                null, null);
    }

    // ==================== 工具方法 ====================

    private Agent requireManageable(Agent agent, CurrentActor actor) {
        if (!authorizationService.canManageAgent(actor, agent)) {
            throw new IllegalArgumentException(Boolean.TRUE.equals(agent.getIsSystem())
                    ? "「" + agent.getName() + "」是系统公共智能体，只有超级管理员可以修改；可以先在界面上复制一份再修改"
                    : "你没有修改智能体「" + agent.getName() + "」的权限");
        }
        return agent;
    }

    private Agent loadManageable(String agentId, CurrentActor actor) {
        Agent agent = agentService.getById(agentId, actor)
                .orElseThrow(() -> new IllegalStateException("智能体已不存在"));
        if (!authorizationService.canManageAgent(actor, agent)) {
            throw new IllegalStateException("你已没有修改该智能体的权限");
        }
        return agent;
    }

    /**
     * 以当前状态为基础的更新对象：{@link AgentService#update} 会把所有非空字段写回，而 Agent 的构造器给状态、温度、
     * 知识库列表等字段设置了默认值，直接 new 一个 Agent 当补丁会覆盖掉现有配置。这里先拷贝现值，调用方只改目标字段。
     */
    static Agent patchFrom(Agent current) {
        Agent patch = new Agent();
        patch.setStatus(current.getStatus());
        patch.setTemperature(current.getTemperature());
        patch.setTopP(current.getTopP());
        patch.setMaxTokens(current.getMaxTokens());
        patch.setTags(current.getTags() != null ? new ArrayList<>(current.getTags()) : new ArrayList<>());
        patch.setKnowledgeBaseIds(new ArrayList<>(currentKnowledgeBaseIds(current)));
        patch.setIsSystem(null);
        return patch;
    }

    private static List<String> currentKnowledgeBaseIds(Agent agent) {
        return agent.getKnowledgeBaseIds() == null ? new ArrayList<>()
                : new ArrayList<>(agent.getKnowledgeBaseIds().stream().filter(id -> id != null && !id.isBlank()).toList());
    }

    private String knowledgeBaseNames(List<String> ids, CurrentActor actor) {
        if (ids.isEmpty()) {
            return "（无）";
        }
        return String.join("、", ids.stream().map(id -> knowledgeBaseRepository.findById(id)
                .map(kb -> authorizationService.canViewKnowledgeBase(actor, kb) ? kb.getName() : "（无权查看的知识库）")
                .orElse("（已删除的知识库）")).toList());
    }

    private String generateCode() {
        String code;
        do {
            code = "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (agentRepository.existsByCode(code));
        return code;
    }

    private static Map<String, Object> preview(List<Map<String, Object>> fields, Map<String, Object> diff, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", fields);
        if (diff != null) {
            m.put("diff", diff);
        }
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    private static Map<String, Object> field(String label, String value) {
        return Map.of("label", label, "value", value == null ? "" : value);
    }

    private static Map<String, Object> longField(String label, String value) {
        return Map.of("label", label, "value", value == null ? "" : value, "multiline", true);
    }

    private static Map<String, Object> diff(String label, String before, String after) {
        return Map.of("label", label, "before", before == null ? "" : before, "after", after == null ? "" : after);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("操作参数序列化失败");
        }
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

    private static String text(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    private static Double number(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 需要是数字");
        }
    }

    private static List<String> textList(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> {
                String v = item.asText("").trim();
                if (!v.isEmpty()) {
                    values.add(v);
                }
            });
        } else if (!node.asText("").isBlank()) {
            for (String part : node.asText().split("[,，、]")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return values;
    }

    private static String required(JsonNode args, String field, int max, String label) {
        String value = text(args, field);
        if (value == null) {
            throw new IllegalArgumentException("请提供" + label);
        }
        checkMax(value, max, label);
        return value;
    }

    private static void checkMax(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(label + "不能超过 " + max + " 字，当前 " + value.length() + " 字");
        }
    }
}
