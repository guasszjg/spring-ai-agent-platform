package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.config.OutboundUrlValidator;
import com.shimeta.mcp.McpServerRequest;
import com.shimeta.mcp.McpServerService;
import com.shimeta.mcp.McpServerView;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentTemplate;
import com.shimeta.model.CreateUserRequest;
import com.shimeta.model.DifyConfigRequest;
import com.shimeta.model.DifyConfigView;
import com.shimeta.model.EmbeddingConfigView;
import com.shimeta.model.GatewayOverview;
import com.shimeta.model.GuardrailPolicy;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.model.LlmProtocolType;
import com.shimeta.model.LlmProviderRequest;
import com.shimeta.model.LlmProviderType;
import com.shimeta.model.LlmProviderView;
import com.shimeta.model.OpenApiKey;
import com.shimeta.model.PageResult;
import com.shimeta.model.PlatformToolUpdateRequest;
import com.shimeta.model.PlatformToolView;
import com.shimeta.model.RerankConfigView;
import com.shimeta.model.UserRole;
import com.shimeta.model.UserStatus;
import com.shimeta.model.UserSummaryDto;
import com.shimeta.rag.dto.RagEvalRunRequest;
import com.shimeta.rag.dto.UpdateFaqRequest;
import com.shimeta.rag.dto.UpdateKnowledgeBaseRequest;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentPromptVersionService;
import com.shimeta.service.AgentService;
import com.shimeta.service.AgentTemplateService;
import com.shimeta.service.DifyConfigService;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.KnowledgeIndexRebuildService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.RagEvaluationService;
import com.shimeta.service.RerankConfigService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.SecurityPlatformService;
import com.shimeta.service.UserAdminService;
import com.shimeta.skill.SkillRequest;
import com.shimeta.skill.SkillService;
import com.shimeta.skill.SkillView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.shimeta.assistant.AssistantWriteActionSupport.bool;
import static com.shimeta.assistant.AssistantWriteActionSupport.enumProp;
import static com.shimeta.assistant.AssistantWriteActionSupport.number;
import static com.shimeta.assistant.AssistantWriteActionSupport.previewBuilder;
import static com.shimeta.assistant.AssistantWriteActionSupport.prop;
import static com.shimeta.assistant.AssistantWriteActionSupport.requiredText;
import static com.shimeta.assistant.AssistantWriteActionSupport.schema;
import static com.shimeta.assistant.AssistantWriteActionSupport.sha256;
import static com.shimeta.assistant.AssistantWriteActionSupport.text;
import static com.shimeta.assistant.AssistantWriteActionSupport.textList;

/**
 * 平台 AI 助手扩展写工具（4.4 补齐执行能力，按 A / B / C / D 四级风险管控）：
 * <ul>
 *   <li><b>A 级（新建）</b>：{@code create_http_tool}、{@code create_skill}、{@code save_agent_as_template}、{@code register_mcp_server}；</li>
 *   <li><b>B 级（修改 + 前后对比 + 基线乐观锁）</b>：{@code update_agent_settings}、{@code update_kb_settings}、{@code update_faq}、{@code rollback_agent_prompt}；</li>
 *   <li><b>C 级（运行任务 + 耗时/成本说明）</b>：{@code rebuild_kb_index}、{@code run_rag_eval}、{@code refresh_mcp_server}；</li>
 *   <li><b>D 级（高危：5 分钟过期、每轮限 1 张、禁串联、必须手输目标名、密钥/密码仅走卡片密码框）</b>：
 *       {@code delete_resource}、{@code manage_user}、{@code manage_gateway_channel}、{@code update_guardrail_policy}、{@code revoke_api_key}。</li>
 * </ul>
 */
@Component
public class AssistantExtendedWriteTools {

    // A 级
    static final String CREATE_HTTP_TOOL = "create_http_tool";
    static final String CREATE_SKILL = "create_skill";
    static final String SAVE_AGENT_AS_TEMPLATE = "save_agent_as_template";
    static final String REGISTER_MCP_SERVER = "register_mcp_server";
    // B 级
    static final String UPDATE_AGENT_SETTINGS = "update_agent_settings";
    static final String UPDATE_KB_SETTINGS = "update_kb_settings";
    static final String UPDATE_FAQ = "update_faq";
    static final String ROLLBACK_AGENT_PROMPT = "rollback_agent_prompt";
    // C 级
    static final String REBUILD_KB_INDEX = "rebuild_kb_index";
    static final String RUN_RAG_EVAL = "run_rag_eval";
    static final String REFRESH_MCP_SERVER = "refresh_mcp_server";
    // D 级
    static final String DELETE_RESOURCE = "delete_resource";
    static final String MANAGE_USER = "manage_user";
    static final String MANAGE_GATEWAY_CHANNEL = "manage_gateway_channel";
    static final String UPDATE_GUARDRAIL_POLICY = "update_guardrail_policy";
    static final String REVOKE_API_KEY = "revoke_api_key";
    // B 级：停用分享链接（可以在调试页重新启用，所以不算高危）
    static final String DISABLE_SHARE_LINK = "disable_share_link";

    private static final Set<String> HANDLED_TOOLS = Set.of(
            CREATE_HTTP_TOOL, CREATE_SKILL, SAVE_AGENT_AS_TEMPLATE, REGISTER_MCP_SERVER,
            UPDATE_AGENT_SETTINGS, UPDATE_KB_SETTINGS, UPDATE_FAQ, ROLLBACK_AGENT_PROMPT, DISABLE_SHARE_LINK,
            REBUILD_KB_INDEX, RUN_RAG_EVAL, REFRESH_MCP_SERVER,
            DELETE_RESOURCE, MANAGE_USER, MANAGE_GATEWAY_CHANNEL, UPDATE_GUARDRAIL_POLICY, REVOKE_API_KEY);

    private static final Set<String> DELETABLE_TYPES = Set.of(
            "agent", "knowledge_base", "document", "faq", "http_tool", "skill", "template", "mcp_server");

    private final AssistantQueryTools queryTools;
    private final AgentService agentService;
    private final AgentTemplateService templateService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ResourceAuthorizationService authorizationService;
    private final LlmGatewayService gatewayService;
    private final EmbeddingConfigService embeddingConfigService;
    private final OpenApiKeyService openApiKeyService;
    private final AssistantWriteActionSupport support;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PlatformToolService platformToolService;
    private SkillService skillService;
    private McpServerService mcpServerService;
    private AgentPromptVersionService promptVersionService;
    private KnowledgeIndexRebuildService rebuildService;
    private RagEvaluationService ragEvaluationService;
    private UserAdminService userAdminService;
    private SecurityPlatformService securityPlatformService;
    private RerankConfigService rerankConfigService;
    private DifyConfigService difyConfigService;
    private OutboundUrlValidator urlValidator;

    @Autowired
    public AssistantExtendedWriteTools(AssistantQueryTools queryTools,
                                       AgentService agentService,
                                       AgentTemplateService templateService,
                                       KnowledgeBaseService knowledgeBaseService,
                                       KnowledgeBaseRepository knowledgeBaseRepository,
                                       ResourceAuthorizationService authorizationService,
                                       LlmGatewayService gatewayService,
                                       EmbeddingConfigService embeddingConfigService,
                                       OpenApiKeyService openApiKeyService,
                                       AssistantActionRepository actionRepository) {
        this.queryTools = queryTools;
        this.agentService = agentService;
        this.templateService = templateService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.authorizationService = authorizationService;
        this.gatewayService = gatewayService;
        this.embeddingConfigService = embeddingConfigService;
        this.openApiKeyService = openApiKeyService;
        this.support = new AssistantWriteActionSupport(actionRepository, this.objectMapper);
    }

    @Autowired(required = false) public void setPlatformToolService(PlatformToolService s) { this.platformToolService = s; }
    @Autowired(required = false) public void setSkillService(SkillService s) { this.skillService = s; }
    @Autowired(required = false) public void setMcpServerService(McpServerService s) { this.mcpServerService = s; }
    @Autowired(required = false) public void setPromptVersionService(AgentPromptVersionService s) { this.promptVersionService = s; }
    @Autowired(required = false) public void setRebuildService(KnowledgeIndexRebuildService s) { this.rebuildService = s; }
    @Autowired(required = false) public void setRagEvaluationService(RagEvaluationService s) { this.ragEvaluationService = s; }
    @Autowired(required = false) public void setUserAdminService(UserAdminService s) { this.userAdminService = s; }
    @Autowired(required = false) public void setSecurityPlatformService(SecurityPlatformService s) { this.securityPlatformService = s; }
    @Autowired(required = false) public void setRerankConfigService(RerankConfigService s) { this.rerankConfigService = s; }
    @Autowired(required = false) public void setDifyConfigService(DifyConfigService s) { this.difyConfigService = s; }
    @Autowired(required = false) public void setUrlValidator(OutboundUrlValidator v) { this.urlValidator = v; }
    private com.shimeta.service.share.ShareLinkService shareLinkService;
    @Autowired(required = false) public void setShareLinkService(com.shimeta.service.share.ShareLinkService s) { this.shareLinkService = s; }

    public boolean supports(String toolName) {
        return toolName != null && HANDLED_TOOLS.contains(toolName);
    }

    public List<AssistantTool> tools() {
        return List.of(
                // ---------- A 级：新建 ----------
                AssistantTool.write(CREATE_HTTP_TOOL, "生成新建 HTTP 工具操作",
                        "新建一个自定义 HTTP 工具（A 级待确认卡片）。若工具需要调用密钥，请告知用户建好后在「构建 → 工具」补充密钥，不要让用户在对话中发送密钥。",
                        schema(Map.of(
                                "name", prop("string", "工具名称（不超过 50 字）"),
                                "title", prop("string", "展示标题，可选，默认同 name"),
                                "description", prop("string", "工具说明/提示词，告诉模型何时调用"),
                                "url", prop("string", "HTTP 接口地址（http:// 或 https://）"),
                                "method", enumProp("HTTP 请求方式，默认 POST", "GET", "POST", "PUT"),
                                "timeoutSeconds", prop("integer", "超时秒数，1~60，默认 8")), "name", "description", "url"),
                        this::draftCreateHttpTool),
                AssistantTool.write(CREATE_SKILL, "生成新建技能操作",
                        "新建一个 Markdown 技能（Skill，A 级待确认卡片）。",
                        schema(Map.of(
                                "name", prop("string", "技能名称，不超过 80 字"),
                                "description", prop("string", "一句话说明触发场景与用途"),
                                "content", prop("string", "技能 Markdown 正文内容"),
                                "requiredTools", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "可选：该技能依赖的工具编码列表")), "name", "description", "content"),
                        this::draftCreateSkill),
                AssistantTool.write(SAVE_AGENT_AS_TEMPLATE, "生成保存场景模板操作",
                        "从现有智能体保存为场景模板，或直接新建一个自定义场景模板（A 级待确认卡片）。",
                        schema(Map.of(
                                "name", prop("string", "模板名称，不超过 50 字"),
                                "agent", prop("string", "可选：来源智能体 ID/编码/名称；传了则自动复制其提示词与参数"),
                                "category", prop("string", "分类，可选"),
                                "description", prop("string", "模板用途说明，可选"),
                                "systemPrompt", prop("string", "系统提示词（未传 agent 时必填）")), "name"),
                        this::draftSaveAgentAsTemplate),
                AssistantTool.adminWrite(REGISTER_MCP_SERVER, "生成登记 MCP 服务操作",
                        "登记一个远程 MCP 服务（Streamable HTTP 或 SSE，仅超级管理员，A 级待确认卡片；新拉取的工具默认待审核不自动放行）。",
                        schema(Map.of(
                                "name", prop("string", "MCP 服务名称"),
                                "url", prop("string", "远程 MCP 端点地址（http:// 或 https://）"),
                                "transport", enumProp("传输协议：STREAMABLE_HTTP（默认）或 SSE", "STREAMABLE_HTTP", "SSE"),
                                "slug", prop("string", "短标识（字母数字下划线），可选"),
                                "remark", prop("string", "备注，可选")), "name", "url"),
                        this::draftRegisterMcpServer),

                // ---------- B 级：修改 ----------
                AssistantTool.write(UPDATE_AGENT_SETTINGS, "生成修改智能体设置操作",
                        "统一修改智能体的模型、主备通道路由、温度、Top P、最大输出长度、上下文记忆轮数、思考模式、知识库检索方式以及绑定的技能和工具（B 级修改对比卡片）。只传要修改的字段即可。"
                                + "修改模型或切换通道前，请先从 get_agent_detail 返回的 availableModelChoices 中确认准确的通道名与模型名，切勿猜测模型名；切换不同通道的模型时须同时传入 primaryChannel 和 modelName。",
                        schema(Map.ofEntries(
                                Map.entry("agent", prop("string", "智能体 ID、编码或名称")),
                                Map.entry("modelName", prop("string", "模型名称（须为目标通道可用模型列表中的准确名称），可选")),
                                Map.entry("primaryChannel", prop("string", "主通道名称或 ID（切换其他通道的模型时必须同时指定）；传 \"DEFAULT\" 表示切回跟随平台默认，可选")),
                                Map.entry("fallbackChannel", prop("string", "备用通道名称或 ID；传 \"DEFAULT\" 表示切回跟随平台默认，可选")),
                                Map.entry("temperature", prop("number", "温度 0~2，可选")),
                                Map.entry("topP", prop("number", "Top P 0~1，可选")),
                                Map.entry("maxTokens", prop("integer", "最大输出长度 128~32768，可选")),
                                Map.entry("memoryTurns", prop("integer", "上下文记忆轮数 0~10，可选")),
                                Map.entry("thinkingMode", enumProp("思考模式：OFF 关闭、AUTO 跟随默认、ON 开启", "OFF", "AUTO", "ON")),
                                Map.entry("knowledgeMode", enumProp("知识库模式：PRE_RETRIEVE 先检索后回答、AGENTIC 模型自主检索", "PRE_RETRIEVE", "AGENTIC")),
                                Map.entry("addSkills", Map.of("type", "array", "items", Map.of("type", "string"), "description", "要关联的技能名称或 ID 列表")),
                                Map.entry("removeSkills", Map.of("type", "array", "items", Map.of("type", "string"), "description", "要取消关联的技能名称或 ID 列表")),
                                Map.entry("addTools", Map.of("type", "array", "items", Map.of("type", "string"), "description", "要启用的平台工具编码或名称列表")),
                                Map.entry("removeTools", Map.of("type", "array", "items", Map.of("type", "string"), "description", "要取消勾选的工具编码或名称列表"))), "agent"),
                        this::draftUpdateAgentSettings),
                AssistantTool.write(UPDATE_KB_SETTINGS, "生成修改知识库检索设置操作",
                        "修改自建知识库的检索方式、Top K、分数阈值、向量/关键词权重与 Rerank 开关（B 级修改对比卡片）。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "searchMethod", enumProp("检索方式：hybrid_search 混合、semantic_search 向量、full_text_search 全文",
                                        "hybrid_search", "semantic_search", "full_text_search"),
                                "topK", prop("integer", "返回切片条数 1~20"),
                                "scoreThreshold", prop("number", "分数阈值 0~1"),
                                "vectorWeight", prop("number", "向量权重 0~1"),
                                "keywordWeight", prop("number", "关键词权重 0~1"),
                                "rerankEnabled", prop("boolean", "是否启用 Rerank 重排"),
                                "rerankMode", enumProp("重排模式：weighted_score 权重融合、reranking_model 重排模型",
                                        "weighted_score", "reranking_model")), "knowledgeBase"),
                        this::draftUpdateKbSettings),
                AssistantTool.write(UPDATE_FAQ, "生成修改 FAQ 操作",
                        "修改知识库中已有 FAQ 的问题、答案、分类、图片/视频链接或启用状态（B 级修改对比卡片）。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "faqId", prop("string", "FAQ ID 或原问题文本"),
                                "question", prop("string", "新问题，可选"),
                                "answer", prop("string", "新答案，可选"),
                                "category", prop("string", "新分类，可选"),
                                "imageUrls", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "新的附图 URL 列表（最多 9 张，传空数组 [] 清空），可选"),
                                "videoUrls", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "新的视频 URL 列表（最多 3 个，传空数组 [] 清空），可选"),
                                "enabled", prop("boolean", "是否启用，可选")), "knowledgeBase", "faqId"),
                        this::draftUpdateFaq),
                AssistantTool.write(DISABLE_SHARE_LINK, "生成停用分享链接操作",
                        "停用智能体的某个分享链接（免登录聊天页），停用后访客发下一条消息即被拒绝；可在调试页「访问入口」重新启用（B 级待确认卡片）。"
                                + "先用 list_share_links 查到链接名称或 ID。新建链接、修改访问码或有效期不支持，请让用户到调试页操作。",
                        schema(Map.of(
                                "agent", prop("string", "智能体的 ID、编码或名称"),
                                "link", prop("string", "分享链接的名称或 ID")), "agent", "link"),
                        this::draftDisableShareLink),
                AssistantTool.write(ROLLBACK_AGENT_PROMPT, "生成回滚提示词操作",
                        "将智能体的系统提示词回滚到指定历史版本（B 级修改对比卡片，展示当前版本与目标版本差异）。",
                        schema(Map.of(
                                "agent", prop("string", "智能体 ID、编码或名称"),
                                "version", prop("string", "目标版本 ID 或版本号（如 \"2\" 或 \"v2\"）")), "agent", "version"),
                        this::draftRollbackAgentPrompt),

                // ---------- C 级：运行任务 ----------
                AssistantTool.write(REBUILD_KB_INDEX, "生成重建知识库索引操作",
                        "触发平台自建知识库后台重建索引（C 级运行卡片，会重新解析并向量化全部文档，注明预计耗时与向量化消耗）。",
                        schema(Map.of("knowledgeBase", prop("string", "知识库名称或 ID")), "knowledgeBase"),
                        this::draftRebuildKbIndex),
                AssistantTool.write(RUN_RAG_EVAL, "生成运行知识库检索评测操作",
                        "对自建知识库的默认评测集发起一次检索评测运行（C 级运行卡片，注明耗时与可能的 Rerank 消耗）。",
                        schema(Map.of(
                                "knowledgeBase", prop("string", "知识库名称或 ID"),
                                "label", prop("string", "本次评测标签，可选")), "knowledgeBase"),
                        this::draftRunRagEval),
                AssistantTool.adminWrite(REFRESH_MCP_SERVER, "生成刷新 MCP 工具操作",
                        "测试远程 MCP 服务连接并重新拉取工具列表（仅超级管理员，C 级运行卡片）。",
                        schema(Map.of("server", prop("string", "MCP 服务名称、slug 或 ID")), "server"),
                        this::draftRefreshMcpServer),

                // ---------- D 级：高危操作（5 分钟过期、每轮限 1 张、禁串联、必须手输目标名、密钥/密码走卡片密码框） ----------
                AssistantTool.write(DELETE_RESOURCE, "生成删除资源高危操作",
                        "【D 级高危】删除智能体、知识库、文档、FAQ、自定义 HTTP 工具、技能、场景模板或 MCP 服务（仅超管）。"
                                + "必须先确认用户本轮明确提出删除；生成高危卡片后，用户须在卡片中手动输入完整资源名称才能执行。",
                        schema(Map.of(
                                "type", enumProp("要删除的资源类型",
                                        "agent", "knowledge_base", "document", "faq", "http_tool", "skill", "template", "mcp_server"),
                                "id", prop("string", "资源 ID、编码或名称"),
                                "parent", prop("string", "删除 document 或 faq 时必传所属知识库名称或 ID")), "type", "id"),
                        this::draftDeleteResource),
                AssistantTool.adminWrite(MANAGE_USER, "生成用户管理高危操作",
                        "【D 级高危·仅超管】新建用户、修改用户角色、启停用户账号或重置用户密码。"
                                + "重置密码或指定初始密码时，严禁向用户索要密码明文——密码由用户在卡片密码框里直接输入，模型不可见。",
                        schema(Map.of(
                                "operation", enumProp("操作类型", "create_user", "change_role", "toggle_status", "reset_password"),
                                "username", prop("string", "目标用户名（create_user 为新用户名，其他为已有用户名或 ID）"),
                                "nickname", prop("string", "昵称（create_user 可选）"),
                                "role", enumProp("角色（create_user / change_role 必填）", "SUPER_ADMIN", "DEVELOPER", "VIEWER"),
                                "status", enumProp("状态（toggle_status 必填）", "ACTIVE", "DISABLED")), "operation", "username"),
                        this::draftManageUser),
                AssistantTool.adminWrite(MANAGE_GATEWAY_CHANNEL, "生成模型网关高危操作",
                        "【D 级高危·仅超管】修改大模型通道 API Key/启停/新建通道，切换激活的向量/Rerank 模型，或更新 Dify 引擎密钥。"
                                + "修改或新建通道密钥时，严禁让用户在对话框发密钥——密钥由用户在卡片密码框中直接填写。",
                        schema(Map.of(
                                "operation", enumProp("操作类型",
                                        "update_llm_key", "toggle_llm_channel", "create_llm_channel",
                                        "activate_embedding", "activate_rerank", "update_dify_key"),
                                "target", prop("string", "目标通道/配置名称或 ID（create_llm_channel 为新通道名称）"),
                                "enabled", prop("boolean", "toggle_llm_channel 时必填：true 启用 / false 停用"),
                                "baseUrl", prop("string", "create_llm_channel 时必填：接口 Base URL"),
                                "defaultModel", prop("string", "create_llm_channel 时必填：默认模型标识"),
                                "protocol", enumProp("create_llm_channel 可选协议，默认 OPENAI", "OPENAI", "OLLAMA")), "operation", "target"),
                        this::draftManageGatewayChannel),
                AssistantTool.write(UPDATE_GUARDRAIL_POLICY, "生成修改护栏策略高危操作",
                        "【D 级高危】修改开放与安全护栏策略（紧急停用 killSwitch、PII 脱敏、敏感词处置、注入检测、输出护栏、RPM 限流、每日 Token 配额、最大输入字数/历史轮数、终端管控模式）。",
                        schema(Map.of(
                                "killSwitch", prop("boolean", "紧急停用开关（true 将立即拦截开放调用）"),
                                "piiMask", prop("boolean", "是否开启 PII 脱敏"),
                                "sensitiveAction", enumProp("敏感词命中动作", "BLOCK", "MASK", "LOG"),
                                "promptInjection", enumProp("提示词注入检测", "BLOCK", "LOG", "OFF"),
                                "outputGuard", prop("boolean", "是否开启输出护栏"),
                                "clientPolicy", enumProp("接入终端管控模式", "OFF", "LOG", "STRICT"),
                                "defaultRpm", prop("integer", "默认每分钟限流次数"),
                                "defaultDailyTokens", prop("integer", "默认每日 Token 配额"),
                                "maxInputChars", prop("integer", "单次最大输入字符数"),
                                "maxHistoryTurns", prop("integer", "最大历史对话轮数"))),
                        this::draftUpdateGuardrailPolicy),
                AssistantTool.write(REVOKE_API_KEY, "生成凭证管控高危操作",
                        "【D 级高危】停用、轮换或吊销（删除）一条开放 API 凭证。用户需在卡片中输入凭证名称确认。",
                        schema(Map.of(
                                "key", prop("string", "凭证名称、前缀或 ID"),
                                "operation", enumProp("操作：disable 停用、rotate 轮换、revoke 永久吊销", "disable", "rotate", "revoke"),
                                "graceHours", prop("integer", "rotate 轮换时的旧 Key 宽限小时数（0~168），可选，默认 24")), "key", "operation"),
                        this::draftRevokeApiKey)
        );
    }

    // ==================== A 级：草稿生成 ====================

    Map<String, Object> draftCreateHttpTool(JsonNode args, ToolContext ctx) {
        requireBean(platformToolService, "工具管理模块");
        String name = requiredText(args, "name", 50, "工具名称");
        String title = text(args, "title");
        if (title == null) title = name;
        String desc = requiredText(args, "description", 1000, "工具说明");
        String url = requiredText(args, "url", 500, "接口地址");
        if (urlValidator != null) {
            urlValidator.validateProviderBaseUrl(url);
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("接口地址必须以 http:// 或 https:// 开头");
        }
        String method = text(args, "method");
        method = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT").contains(method)) {
            throw new IllegalArgumentException("请求方式仅支持 GET / POST / PUT");
        }
        Double timeout = number(args, "timeoutSeconds");
        int timeoutSec = timeout == null ? 8 : Math.max(1, Math.min(60, timeout.intValue()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("title", title);
        payload.put("description", desc);
        payload.put("url", url);
        payload.put("method", method);
        payload.put("timeoutSeconds", timeoutSec);

        var preview = previewBuilder()
                .addField("名称", name)
                .addField("请求方式", method)
                .addField("接口地址", url)
                .addField("超时时间", timeoutSec + " 秒")
                .addLongField("工具说明", desc)
                .note("如接口需要鉴权密钥，请创建后前往「构建 → 工具」页面补充配置。")
                .build();
        return support.createPendingCard(ctx, CREATE_HTTP_TOOL, "A", "新建自定义 HTTP 工具：" + title, payload, preview, "HTTP_TOOL");
    }

    Map<String, Object> draftCreateSkill(JsonNode args, ToolContext ctx) {
        requireBean(skillService, "技能库模块");
        String name = requiredText(args, "name", 80, "技能名称");
        String desc = requiredText(args, "description", 300, "技能说明");
        String content = requiredText(args, "content", 16000, "技能 Markdown 正文");
        List<String> reqTools = textList(args, "requiredTools");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", desc);
        payload.put("content", content);
        payload.put("requiredTools", reqTools);

        var preview = previewBuilder()
                .addField("技能名称", name)
                .addField("触发说明", desc)
                .addField("所需工具", reqTools.isEmpty() ? "无" : String.join("、", reqTools))
                .addLongField("正文预览", content)
                .build();
        return support.createPendingCard(ctx, CREATE_SKILL, "A", "新建技能：" + name, payload, preview, "SKILL");
    }

    Map<String, Object> draftSaveAgentAsTemplate(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        String name = requiredText(args, "name", 50, "模板名称");
        String agentRef = text(args, "agent");
        Agent source = null;
        if (agentRef != null) {
            AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(agentRef, actor);
            if (match.agent() == null) {
                return match.response();
            }
            source = match.agent();
        }
        String prompt = text(args, "systemPrompt");
        if (prompt == null && source != null) {
            prompt = source.getSystemPrompt();
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请提供系统提示词，或指定来源智能体");
        }
        String category = text(args, "category");
        if (category == null) {
            category = source != null && source.getCategory() != null ? source.getCategory() : "通用智能";
        }
        String desc = text(args, "description");
        if (desc == null && source != null) {
            desc = source.getDescription();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("category", category);
        payload.put("description", desc);
        payload.put("systemPrompt", prompt);
        if (source != null) {
            payload.put("sourceAgentId", source.getId());
            payload.put("modelName", source.getModelName());
            payload.put("temperature", source.getTemperature());
            payload.put("maxTokens", source.getMaxTokens());
        }

        var pb = previewBuilder()
                .addField("模板名称", name)
                .addField("分类", category);
        if (source != null) {
            pb.addField("来源智能体", source.getName());
        }
        if (desc != null) {
            pb.addField("说明", desc);
        }
        pb.addLongField("系统提示词", prompt);
        return support.createPendingCard(ctx, SAVE_AGENT_AS_TEMPLATE, "A", "新建场景模板：" + name, payload, pb.build(), "TEMPLATE");
    }

    Map<String, Object> draftRegisterMcpServer(JsonNode args, ToolContext ctx) {
        requireSuperAdmin(ctx.actor(), "登记 MCP 服务");
        requireBean(mcpServerService, "MCP 服务模块");
        String name = requiredText(args, "name", 80, "MCP 服务名称");
        String url = requiredText(args, "url", 500, "MCP 服务地址");
        if (urlValidator != null) {
            urlValidator.validateProviderBaseUrl(url);
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("MCP 地址必须以 http:// 或 https:// 开头");
        }
        String transport = text(args, "transport");
        transport = transport == null ? "STREAMABLE_HTTP" : transport.toUpperCase(Locale.ROOT);
        if (!Set.of("STREAMABLE_HTTP", "SSE").contains(transport)) {
            throw new IllegalArgumentException("传输协议仅支持 STREAMABLE_HTTP 或 SSE");
        }
        String slug = text(args, "slug");
        String remark = text(args, "remark");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("url", url);
        payload.put("transport", transport);
        payload.put("slug", slug);
        payload.put("remark", remark);

        var pb = previewBuilder()
                .addField("服务名称", name)
                .addField("传输协议", transport)
                .addField("远程地址", url);
        if (slug != null) pb.addField("短标识", slug);
        if (remark != null) pb.addField("备注", remark);
        pb.note("登记后拉取的远程工具默认处于「待审核」状态，需在「构建 → 工具 → MCP 服务」人工审核启用后才可供智能体调用。");
        return support.createPendingCard(ctx, REGISTER_MCP_SERVER, "A", "登记 MCP 服务：" + name, payload, pb.build(), "MCP_SERVER");
    }

    // ==================== B 级：修改（对比 + 基线乐观锁） ====================

    Map<String, Object> draftUpdateAgentSettings(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageableAgent(match.agent(), actor);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        var pb = previewBuilder().addField("智能体", agent.getName());
        int changes = 0;

        // 1. 主备通道与模型路由（强校验可选通道与模型名，杜绝猜测不存在的模型名或漏设主通道导致切换无效）
        String primaryRef = text(args, "primaryChannel");
        String modelName = text(args, "modelName");
        LlmProviderView resolvedPrimary = null;
        String currentPrimaryName = resolveProviderNameOrDefault(agent.getPrimaryProviderId());
        if (primaryRef != null) {
            if ("DEFAULT".equalsIgnoreCase(primaryRef) || "跟随默认".equals(primaryRef)) {
                if (agent.getPrimaryProviderId() != null && !agent.getPrimaryProviderId().isBlank()) {
                    payload.put("primaryProviderId", "");
                    pb.addDiff("主通道", currentPrimaryName, "跟随平台默认");
                    changes++;
                }
            } else {
                resolvedPrimary = resolveProviderForAgent(primaryRef);
                if (!Objects.equals(resolvedPrimary.getId(), agent.getPrimaryProviderId())) {
                    payload.put("primaryProviderId", resolvedPrimary.getId());
                    pb.addDiff("主通道", currentPrimaryName, resolvedPrimary.getName());
                    changes++;
                }
            }
        }
        String fallbackRef = text(args, "fallbackChannel");
        if (fallbackRef != null) {
            String currentFallbackName = resolveProviderNameOrDefault(agent.getFallbackProviderId());
            if ("DEFAULT".equalsIgnoreCase(fallbackRef) || "跟随默认".equals(fallbackRef)) {
                if (agent.getFallbackProviderId() != null && !agent.getFallbackProviderId().isBlank()) {
                    payload.put("fallbackProviderId", "");
                    pb.addDiff("备用通道", currentFallbackName, "跟随平台默认");
                    changes++;
                }
            } else {
                LlmProviderView fb = resolveProviderForAgent(fallbackRef);
                if (!Objects.equals(fb.getId(), agent.getFallbackProviderId())) {
                    payload.put("fallbackProviderId", fb.getId());
                    pb.addDiff("备用通道", currentFallbackName, fb.getName());
                    changes++;
                }
            }
        }
        if (modelName != null) {
            String effectiveModel = modelName;
            if (resolvedPrimary != null) {
                effectiveModel = matchModelOnProvider(resolvedPrimary, modelName);
            } else {
                // 未显式传 primaryChannel 时，在所有可选通道中按精确→模糊查找所属通道，自动补全 primaryProviderId
                ModelChannelMatch matched = resolveModelAcrossSelectableChannels(modelName, agent.getPrimaryProviderId());
                if (matched != null) {
                    effectiveModel = matched.model();
                    if (!Objects.equals(matched.provider().getId(), agent.getPrimaryProviderId())) {
                        payload.put("primaryProviderId", matched.provider().getId());
                        pb.addDiff("主通道", currentPrimaryName, matched.provider().getName());
                        changes++;
                    }
                }
            }
            if (!Objects.equals(effectiveModel, agent.getModelName())) {
                payload.put("modelName", effectiveModel);
                pb.addDiff("模型", blankOr(agent.getModelName(), "跟随通道默认"), effectiveModel);
                changes++;
            }
        } else if (resolvedPrimary != null && resolvedPrimary.getDefaultModel() != null && !resolvedPrimary.getDefaultModel().isBlank()) {
            // 切换主通道但未指定模型时，同步切到目标通道的默认模型，避免旧通道的模型名在新通道下校验失败
            String targetDefault = resolvedPrimary.getDefaultModel().trim();
            if (!Objects.equals(targetDefault, agent.getModelName())) {
                payload.put("modelName", targetDefault);
                pb.addDiff("模型", blankOr(agent.getModelName(), "跟随通道默认"), targetDefault);
                changes++;
            }
        }

        // 2. 数值与枚举参数
        Double temperature = number(args, "temperature");
        if (temperature != null) {
            if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("温度必须在 0~2 之间");
            if (!Objects.equals(temperature, agent.getTemperature())) {
                payload.put("temperature", temperature);
                pb.addDiff("温度 (temperature)", String.valueOf(agent.getTemperature()), String.valueOf(temperature));
                changes++;
            }
        }
        Double topP = number(args, "topP");
        if (topP != null) {
            if (topP < 0 || topP > 1) throw new IllegalArgumentException("Top P 必须在 0~1 之间");
            if (!Objects.equals(topP, agent.getTopP())) {
                payload.put("topP", topP);
                pb.addDiff("Top P", String.valueOf(agent.getTopP()), String.valueOf(topP));
                changes++;
            }
        }
        Double maxTokensNum = number(args, "maxTokens");
        if (maxTokensNum != null) {
            int maxTokens = maxTokensNum.intValue();
            if (maxTokens < 128 || maxTokens > 32768) throw new IllegalArgumentException("maxTokens 必须在 128~32768 之间");
            if (!Objects.equals(maxTokens, agent.getMaxTokens())) {
                payload.put("maxTokens", maxTokens);
                pb.addDiff("最大输出长度", String.valueOf(agent.getMaxTokens()), String.valueOf(maxTokens));
                changes++;
            }
        }
        Double memNum = number(args, "memoryTurns");
        if (memNum != null) {
            int mem = memNum.intValue();
            if (memNum != Math.floor(memNum) || mem < 0 || mem > Agent.MAX_MEMORY_TURNS) {
                throw new IllegalArgumentException("上下文记忆轮数须为 0~" + Agent.MAX_MEMORY_TURNS + " 的整数");
            }
            if (mem != agent.resolveMemoryTurns()) {
                payload.put("memoryTurns", mem);
                pb.addDiff("上下文记忆轮数", agent.resolveMemoryTurns() + " 轮", mem + " 轮");
                changes++;
            }
        }
        String thinking = text(args, "thinkingMode");
        if (thinking != null) {
            String norm = Agent.normalizeThinkingMode(thinking);
            if (!norm.equals(Agent.normalizeThinkingMode(agent.getThinkingMode()))) {
                payload.put("thinkingMode", norm);
                pb.addDiff("思考模式", Agent.normalizeThinkingMode(agent.getThinkingMode()), norm);
                changes++;
            }
        }
        String kbMode = text(args, "knowledgeMode");
        if (kbMode != null) {
            String norm = Agent.normalizeKnowledgeMode(kbMode);
            if (!norm.equals(Agent.normalizeKnowledgeMode(agent.getKnowledgeMode()))) {
                payload.put("knowledgeMode", norm);
                pb.addDiff("知识库检索方式", Agent.normalizeKnowledgeMode(agent.getKnowledgeMode()), norm);
                changes++;
            }
        }

        // 3. 技能绑定增删
        List<String> addSkills = textList(args, "addSkills");
        List<String> removeSkills = textList(args, "removeSkills");
        if (!addSkills.isEmpty() || !removeSkills.isEmpty()) {
            requireBean(skillService, "技能库模块");
            List<SkillView> visible = skillService.list(actor);
            Set<String> nextIds = new LinkedHashSet<>(agent.getSkillIds() == null ? List.of() : agent.getSkillIds());
            List<String> beforeNames = skillNamesOf(nextIds, visible);
            for (String ref : addSkills) {
                SkillView s = resolveSkill(ref, visible);
                nextIds.add(s.id());
            }
            for (String ref : removeSkills) {
                SkillView s = visible.stream()
                        .filter(v -> ref.equalsIgnoreCase(v.id()) || ref.equalsIgnoreCase(v.name()))
                        .findFirst().orElse(null);
                nextIds.remove(s != null ? s.id() : ref);
            }
            List<String> nextList = new ArrayList<>(nextIds);
            if (!nextList.equals(agent.getSkillIds() == null ? List.of() : agent.getSkillIds())) {
                payload.put("skillIds", nextList);
                pb.addDiff("关联技能", joinOrEmpty(beforeNames), joinOrEmpty(skillNamesOf(nextIds, visible)));
                changes++;
            }
        }

        // 4. 工具绑定增删
        List<String> addTools = textList(args, "addTools");
        List<String> removeTools = textList(args, "removeTools");
        if (!addTools.isEmpty() || !removeTools.isEmpty()) {
            List<String> beforeToolNames = queryTools.enabledToolNames(agent.getToolsConfig());
            String updatedJson = computeUpdatedToolsConfig(agent.getToolsConfig(), addTools, removeTools);
            List<String> afterToolNames = queryTools.enabledToolNames(updatedJson);
            if (!beforeToolNames.equals(afterToolNames)) {
                payload.put("toolsConfig", updatedJson);
                pb.addDiff("启用工具", joinOrEmpty(beforeToolNames), joinOrEmpty(afterToolNames));
                changes++;
            }
        }

        if (changes == 0) {
            throw new IllegalArgumentException("未检测到任何配置变化，无需修改");
        }
        payload.put("baselineSha256", agentSettingsFingerprint(agent));
        return support.createPendingCard(ctx, UPDATE_AGENT_SETTINGS, "B",
                "修改智能体配置：" + agent.getName(), payload, pb.build(), "AGENT");
    }

    Map<String, Object> draftUpdateKbSettings(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kbId", kb.getId());
        var pb = previewBuilder().addField("知识库", kb.getName());
        int changes = 0;

        String method = text(args, "searchMethod");
        if (method != null) {
            if (!Set.of("hybrid_search", "semantic_search", "full_text_search").contains(method)) {
                throw new IllegalArgumentException("检索方式仅支持 hybrid_search / semantic_search / full_text_search");
            }
            if (!method.equals(kb.getSearchMethod())) {
                payload.put("searchMethod", method);
                pb.addDiff("检索方式", blankOr(kb.getSearchMethod(), "hybrid_search"), method);
                changes++;
            }
        }
        Double topKNum = number(args, "topK");
        if (topKNum != null) {
            int topK = topKNum.intValue();
            if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK 必须在 1~20 之间");
            if (!Objects.equals(topK, kb.getTopK())) {
                payload.put("topK", topK);
                pb.addDiff("Top K", String.valueOf(kb.getTopK()), String.valueOf(topK));
                changes++;
            }
        }
        Double threshold = number(args, "scoreThreshold");
        if (threshold != null) {
            if (threshold < 0 || threshold > 1) throw new IllegalArgumentException("分数阈值必须在 0~1 之间");
            if (!Objects.equals(threshold, kb.getScoreThreshold())) {
                payload.put("scoreThreshold", threshold);
                pb.addDiff("分数阈值", String.valueOf(kb.getScoreThreshold()), String.valueOf(threshold));
                changes++;
            }
        }
        Double vw = number(args, "vectorWeight");
        if (vw != null) {
            if (vw < 0 || vw > 1) throw new IllegalArgumentException("向量权重必须在 0~1 之间");
            if (!Objects.equals(vw, kb.getVectorWeight())) {
                payload.put("vectorWeight", vw);
                pb.addDiff("向量权重", String.valueOf(kb.getVectorWeight()), String.valueOf(vw));
                changes++;
            }
        }
        Double kw = number(args, "keywordWeight");
        if (kw != null) {
            if (kw < 0 || kw > 1) throw new IllegalArgumentException("关键词权重必须在 0~1 之间");
            if (!Objects.equals(kw, kb.getKeywordWeight())) {
                payload.put("keywordWeight", kw);
                pb.addDiff("关键词权重", String.valueOf(kb.getKeywordWeight()), String.valueOf(kw));
                changes++;
            }
        }
        Boolean rerank = bool(args, "rerankEnabled");
        if (rerank != null && !rerank.equals(Boolean.TRUE.equals(kb.getRerankEnabled()))) {
            payload.put("rerankEnabled", rerank);
            pb.addDiff("Rerank 重排", Boolean.TRUE.equals(kb.getRerankEnabled()) ? "开启" : "关闭", rerank ? "开启" : "关闭");
            changes++;
        }
        String rerankMode = text(args, "rerankMode");
        if (rerankMode != null) {
            if (!Set.of("weighted_score", "reranking_model").contains(rerankMode)) {
                throw new IllegalArgumentException("rerankMode 仅支持 weighted_score 或 reranking_model");
            }
            if (!rerankMode.equals(kb.getRerankMode())) {
                payload.put("rerankMode", rerankMode);
                pb.addDiff("重排模式", blankOr(kb.getRerankMode(), "weighted_score"), rerankMode);
                changes++;
            }
        }
        if (changes == 0) {
            throw new IllegalArgumentException("未检测到任何检索参数变化，无需修改");
        }
        payload.put("baselineSha256", kbSettingsFingerprint(kb));
        return support.createPendingCard(ctx, UPDATE_KB_SETTINGS, "B",
                "修改知识库检索参数：" + kb.getName(), payload, pb.build(), "KNOWLEDGE_BASE");
    }

    Map<String, Object> draftUpdateFaq(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
        }
        String faqRef = requiredText(args, "faqId", 300, "FAQ ID 或原问题");
        KnowledgeFaq faq = resolveFaq(kb.getId(), faqRef, actor);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kbId", kb.getId());
        payload.put("faqId", faq.getId());
        var pb = previewBuilder()
                .addField("知识库", kb.getName())
                .addField("FAQ ID", faq.getId());
        int changes = 0;

        String q = text(args, "question");
        if (q != null && !q.equals(faq.getQuestion())) {
            payload.put("question", q);
            pb.addDiff("问题", faq.getQuestion(), q);
            changes++;
        }
        String a = text(args, "answer");
        if (a != null && !a.equals(faq.getAnswer())) {
            payload.put("answer", a);
            pb.addDiff("答案", faq.getAnswer(), a);
            changes++;
        }
        String cat = text(args, "category");
        if (cat != null && !cat.equals(faq.getCategory())) {
            payload.put("category", cat);
            pb.addDiff("分类", faq.getCategory(), cat);
            changes++;
        }
        Boolean enabled = bool(args, "enabled");
        if (enabled != null && !enabled.equals(!Boolean.FALSE.equals(faq.getEnabled()))) {
            payload.put("enabled", enabled);
            pb.addDiff("状态", !Boolean.FALSE.equals(faq.getEnabled()) ? "启用" : "停用", enabled ? "启用" : "停用");
            changes++;
        }
        if (args != null && args.has("imageUrls") && !args.get("imageUrls").isNull()) {
            List<String> newImages = AssistantWriteTools.parseMediaUrls(args, "imageUrls", KnowledgeBaseService.MAX_FAQ_IMAGES, "图片");
            List<String> oldImages = parseJsonStringList(faq.getImageUrls());
            if (!newImages.equals(oldImages)) {
                payload.put("imageUrls", newImages);
                pb.addDiff("附图", oldImages.isEmpty() ? "无" : oldImages.size() + " 张", newImages.isEmpty() ? "无" : newImages.size() + " 张");
                changes++;
            }
        }
        if (args != null && args.has("videoUrls") && !args.get("videoUrls").isNull()) {
            List<String> newVideos = AssistantWriteTools.parseMediaUrls(args, "videoUrls", KnowledgeBaseService.MAX_FAQ_VIDEOS, "视频");
            List<String> oldVideos = parseJsonStringList(faq.getVideoUrls());
            if (!newVideos.equals(oldVideos)) {
                payload.put("videoUrls", newVideos);
                pb.addDiff("视频", oldVideos.isEmpty() ? "无" : oldVideos.size() + " 个", newVideos.isEmpty() ? "无" : newVideos.size() + " 个");
                changes++;
            }
        }
        if (changes == 0) {
            throw new IllegalArgumentException("FAQ 内容无变化，无需修改");
        }
        payload.put("baselineSha256", faqFingerprint(faq));
        return support.createPendingCard(ctx, UPDATE_FAQ, "B",
                "修改 FAQ：「" + kb.getName() + "」", payload, pb.build(), "KNOWLEDGE_BASE");
    }

    private String faqFingerprint(KnowledgeFaq faq) {
        return sha256(faq.getQuestion() + "|" + faq.getAnswer() + "|" + faq.getCategory() + "|" + faq.getEnabled()
                + "|" + blankOr(faq.getImageUrls(), "") + "|" + blankOr(faq.getVideoUrls(), ""));
    }

    private List<String> parseJsonStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> list = new ArrayList<>();
            for (JsonNode n : node) {
                if (n != null && !n.isNull() && !n.asText("").isBlank()) {
                    list.add(n.asText("").trim());
                }
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    Map<String, Object> draftRollbackAgentPrompt(JsonNode args, ToolContext ctx) {
        requireBean(promptVersionService, "提示词版本管理模块");
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageableAgent(match.agent(), actor);
        String versionRef = requiredText(args, "version", 64, "目标版本");
        String versionId = versionRef;
        if (versionRef.matches("(?i)v?\\d+")) {
            int versionNo = Integer.parseInt(versionRef.replaceAll("(?i)^v", ""));
            versionId = promptVersionService.list(agent.getId(), actor).stream()
                    .filter(v -> Objects.equals(String.valueOf(v.get("versionNo")), String.valueOf(versionNo)))
                    .map(v -> String.valueOf(v.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未找到智能体第 " + versionNo + " 版提示词"));
        }
        Map<String, Object> detail = promptVersionService.get(agent.getId(), versionId, actor);
        String targetPrompt = String.valueOf(detail.getOrDefault("systemPrompt", ""));
        String current = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt();
        if (targetPrompt.equals(current)) {
            throw new IllegalArgumentException("目标版本的提示词与当前线上版本一致，无需回滚");
        }
        Object vNo = detail.get("versionNo");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        payload.put("versionId", versionId);
        payload.put("versionNo", vNo);
        payload.put("systemPrompt", targetPrompt);
        payload.put("baselineSha256", sha256(current));

        var preview = previewBuilder()
                .addField("智能体", agent.getName())
                .addField("目标版本", vNo != null ? "v" + vNo : versionId)
                .setDiff("系统提示词", current, targetPrompt)
                .note("回滚会生成一个新的历史版本记录，可随时再次切回。")
                .build();
        return support.createPendingCard(ctx, ROLLBACK_AGENT_PROMPT, "B",
                "回滚提示词：" + agent.getName() + (vNo != null ? " → v" + vNo : ""), payload, preview, "AGENT");
    }

    // ==================== C 级：运行任务（注明耗时与成本） ====================

    Map<String, Object> draftRebuildKbIndex(JsonNode args, ToolContext ctx) {
        requireBean(rebuildService, "索引重建模块");
        CurrentActor actor = ctx.actor();
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
        }
        if ("DIFY".equalsIgnoreCase(kb.getProvider())) {
            throw new IllegalArgumentException("Dify 外部知识库不支持在平台侧重建索引");
        }
        if (embeddingConfigService.getActiveConfig().isEmpty()) {
            throw new IllegalArgumentException("当前未激活向量模型，无法重建索引；请先到「模型网关 → 向量模型」激活");
        }
        int docCount = kb.getDocumentCount() == null ? 0 : kb.getDocumentCount();
        Map<String, Object> payload = Map.of("kbId", kb.getId(), "kbName", kb.getName());
        var preview = previewBuilder()
                .addField("知识库", kb.getName())
                .addField("文档数量", docCount + " 篇")
                .addField("操作性质", "后台异步重建索引（不修改知识库配置）")
                .note("预计耗时：约 " + Math.max(10, docCount * 5) + " 秒起（取决于文档大小）；将重新调用向量模型对全部切片计算向量并产生相应 Embedding 消耗。")
                .build();
        return support.createPendingCard(ctx, REBUILD_KB_INDEX, "C",
                "重建知识库索引：" + kb.getName(), payload, preview, "KNOWLEDGE_BASE");
    }

    Map<String, Object> draftRunRagEval(JsonNode args, ToolContext ctx) {
        requireBean(ragEvaluationService, "检索评测模块");
        CurrentActor actor = ctx.actor();
        KnowledgeBase kb = queryTools.resolveKnowledgeBase(text(args, "knowledgeBase"), actor);
        if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
            throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
        }
        String label = text(args, "label");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kbId", kb.getId());
        payload.put("kbName", kb.getName());
        if (label != null) payload.put("label", label);

        var pb = previewBuilder()
                .addField("知识库", kb.getName())
                .addField("评测集", RagEvaluationService.DEFAULT_SET_NAME);
        if (label != null) pb.addField("运行标签", label);
        pb.note("将在后台按当前检索设置逐题运行评测集；若知识库开启了 Rerank 模型，会产生对应重排调用消耗。");
        return support.createPendingCard(ctx, RUN_RAG_EVAL, "C",
                "运行检索评测：" + kb.getName(), payload, pb.build(), "KNOWLEDGE_BASE");
    }

    Map<String, Object> draftRefreshMcpServer(JsonNode args, ToolContext ctx) {
        requireSuperAdmin(ctx.actor(), "刷新 MCP 工具列表");
        requireBean(mcpServerService, "MCP 服务模块");
        McpServerView server = resolveMcpServer(requiredText(args, "server", 80, "MCP 服务"));
        Map<String, Object> payload = Map.of("serverId", server.getId(), "serverName", server.getName());
        var preview = previewBuilder()
                .addField("MCP 服务", server.getName())
                .addField("当前工具数", server.getToolCount() + "（已启用 " + server.getEnabledToolCount() + "）")
                .note("将主动连接远程 MCP 端点重新探测并同步工具清单，通常耗时 2~10 秒；新增工具默认进入待审核状态。")
                .build();
        return support.createPendingCard(ctx, REFRESH_MCP_SERVER, "C",
                "刷新 MCP 工具列表：" + server.getName(), payload, preview, "MCP_SERVER");
    }

    // ==================== D 级：高危操作（5 分钟过期、每轮限 1 张、禁串联、必须手输目标名、密码框不经模型） ====================

    Map<String, Object> draftDeleteResource(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        String type = requiredText(args, "type", 32, "资源类型").toLowerCase(Locale.ROOT);
        if (!DELETABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持删除的资源类型：" + type);
        }
        String ref = requiredText(args, "id", 120, "资源 ID 或名称");
        if (AssistantWriteActionSupport.ACTION_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("高危删除操作不能引用未确认的待创建卡片");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        var pb = previewBuilder();
        String targetName;
        String resourceType;

        switch (type) {
            case "agent" -> {
                AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(ref, actor);
                if (match.agent() == null) return match.response();
                Agent agent = requireManageableAgent(match.agent(), actor);
                targetName = agent.getName();
                resourceType = "AGENT";
                payload.put("id", agent.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "智能体").addField("名称", targetName).addField("ID", agent.getId());
                pb.addImpact("智能体及其提示词历史版本将被永久删除且无法恢复");
                if (agent.getCallCount() != null && agent.getCallCount() > 0) {
                    pb.addImpact("历史累计调用 " + agent.getCallCount() + " 次，关联的开放 API 调用将立即失效");
                }
            }
            case "knowledge_base" -> {
                KnowledgeBase kb = queryTools.resolveKnowledgeBase(ref, actor);
                if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
                    throw new IllegalArgumentException("你没有删除知识库「" + kb.getName() + "」的权限");
                }
                targetName = kb.getName();
                resourceType = "KNOWLEDGE_BASE";
                payload.put("id", kb.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "知识库").addField("名称", targetName)
                        .addField("包含内容", (kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + " 篇文档、"
                                + (kb.getFaqCount() == null ? 0 : kb.getFaqCount()) + " 条 FAQ");
                List<String> boundAgents = findAgentsBindingKb(kb.getId(), actor);
                if (!boundAgents.isEmpty()) {
                    pb.addImpact("以下智能体正绑定该知识库，删除后将无法检索：" + String.join("、", boundAgents));
                }
                pb.addImpact("知识库内的全部文档切片、向量索引与 FAQ 将被永久清除");
            }
            case "document" -> {
                String kbRef = requiredText(args, "parent", 100, "所属知识库");
                KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
                if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
                    throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
                }
                PageResult<KnowledgeDocument> docs = knowledgeBaseService.searchDocuments(kb.getId(), null, null, 1, 200, actor);
                KnowledgeDocument doc = (docs != null && docs.getRecords() != null ? docs.getRecords() : List.<KnowledgeDocument>of()).stream()
                        .filter(d -> ref.equalsIgnoreCase(d.getId()) || ref.equalsIgnoreCase(d.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("知识库「" + kb.getName() + "」中未找到文档「" + ref + "」"));
                targetName = doc.getName();
                resourceType = "KNOWLEDGE_DOCUMENT";
                payload.put("parentId", kb.getId());
                payload.put("id", doc.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "知识库文档").addField("所属知识库", kb.getName()).addField("文档名称", targetName);
                pb.addImpact("文档及其全部切片和向量将被永久删除");
            }
            case "faq" -> {
                String kbRef = requiredText(args, "parent", 100, "所属知识库");
                KnowledgeBase kb = queryTools.resolveKnowledgeBase(kbRef, actor);
                if (!authorizationService.canManageKnowledgeBase(actor, kb)) {
                    throw new IllegalArgumentException("你没有管理知识库「" + kb.getName() + "」的权限");
                }
                KnowledgeFaq faq = resolveFaq(kb.getId(), ref, actor);
                targetName = faq.getQuestion();
                resourceType = "KNOWLEDGE_FAQ";
                payload.put("parentId", kb.getId());
                payload.put("id", faq.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "知识库 FAQ").addField("所属知识库", kb.getName()).addField("问题", targetName);
                pb.addImpact("该条 FAQ 问答对及索引将被永久删除");
            }
            case "http_tool" -> {
                requireBean(platformToolService, "工具管理模块");
                PlatformToolView tool = platformToolService.list().stream()
                        .filter(t -> ref.equalsIgnoreCase(t.getId()) || ref.equalsIgnoreCase(t.getCode()) || ref.equalsIgnoreCase(t.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未找到工具「" + ref + "」"));
                if (Boolean.TRUE.equals(tool.getBuiltin())) {
                    throw new IllegalArgumentException("平台内置工具受保护，不允许删除");
                }
                targetName = tool.getTitle() != null ? tool.getTitle() : tool.getName();
                resourceType = "HTTP_TOOL";
                payload.put("id", tool.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "自定义 HTTP 工具").addField("名称", targetName).addField("编码", tool.getCode());
                pb.addImpact("已勾选该工具的智能体将无法再调用它");
            }
            case "skill" -> {
                requireBean(skillService, "技能库模块");
                SkillView skill = resolveSkill(ref, skillService.list(actor));
                if (skill.isSystem() && !actor.isSuperAdmin()) {
                    throw new IllegalArgumentException("系统内置技能仅超级管理员可删除");
                }
                targetName = skill.name();
                resourceType = "SKILL";
                payload.put("id", skill.id());
                payload.put("name", targetName);
                pb.addField("资源类型", "技能").addField("名称", targetName);
                if (skill.agentCount() > 0) {
                    pb.addImpact("当前有 " + skill.agentCount() + " 个智能体关联了此技能，删除后将失去该技能能力");
                }
                pb.addImpact("技能内容及历史版本将被永久删除");
            }
            case "template" -> {
                AgentTemplate tpl = templateService.list(null, null, actor).stream()
                        .filter(t -> ref.equalsIgnoreCase(t.getId()) || ref.equalsIgnoreCase(t.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未找到场景模板「" + ref + "」"));
                if (Boolean.TRUE.equals(tpl.getIsBuiltin())) {
                    throw new IllegalArgumentException("系统预置模板不允许删除");
                }
                targetName = tpl.getName();
                resourceType = "TEMPLATE";
                payload.put("id", tpl.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "场景模板").addField("名称", targetName);
                pb.addImpact("模板删除后不可恢复（已基于它创建的智能体不受影响）");
            }
            case "mcp_server" -> {
                requireSuperAdmin(actor, "删除 MCP 服务");
                requireBean(mcpServerService, "MCP 服务模块");
                McpServerView server = resolveMcpServer(ref);
                targetName = server.getName();
                resourceType = "MCP_SERVER";
                payload.put("id", server.getId());
                payload.put("name", targetName);
                pb.addField("资源类型", "MCP 服务").addField("名称", targetName)
                        .addField("已启用工具数", String.valueOf(server.getEnabledToolCount()));
                pb.addImpact("该 MCP 服务及其下 " + server.getToolCount() + " 个工具将被永久移除，依赖这些工具的智能体将无法调用");
            }
            default -> throw new IllegalArgumentException("不支持删除的资源类型：" + type);
        }

        pb.confirmTargetName(targetName)
                .note("此操作不可撤销！请在下方输入框手动输入「" + targetName + "」后点击确认。");
        return support.createPendingCard(ctx, DELETE_RESOURCE, "D",
                "删除" + deleteTypeLabel(type) + "：" + targetName, payload, pb.build(), resourceType);
    }

    Map<String, Object> draftManageUser(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        requireSuperAdmin(actor, "管理平台用户与角色");
        requireBean(userAdminService, "用户管理模块");
        String op = requiredText(args, "operation", 32, "操作类型").toLowerCase(Locale.ROOT);
        String usernameRef = requiredText(args, "username", 64, "用户名");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", op);
        var pb = previewBuilder();
        String title;
        String targetName;

        switch (op) {
            case "create_user" -> {
                String username = usernameRef.toLowerCase(Locale.ROOT);
                if (!username.matches("^[a-zA-Z0-9_-]{3,32}$")) {
                    throw new IllegalArgumentException("用户名需为 3~32 位字母、数字、下划线或短横线");
                }
                UserRole role = UserRole.fromRaw(requiredText(args, "role", 32, "初始角色"));
                String nickname = text(args, "nickname");
                if (nickname == null) nickname = username;
                targetName = username;
                payload.put("username", username);
                payload.put("nickname", nickname);
                payload.put("role", role.getCode());
                title = "新建用户：" + username + "（" + role.getName() + "）";
                pb.addField("用户名", username)
                        .addField("昵称", nickname)
                        .addField("分配角色", role.getName())
                        .addImpact("将新增一个可登录平台的账号，角色权限为「" + role.getName() + "」")
                        .secretInput("初始临时密码（留空则自动生成）", "至少 8 位，留空自动生成随机临时密码", false, 8)
                        .confirmTargetName(targetName)
                        .note("密码仅由卡片直传后端，模型不可见；若留空则确认后自动生成临时密码并仅显示一次。");
            }
            case "change_role" -> {
                UserSummaryDto user = resolveUser(usernameRef);
                UserRole newRole = UserRole.fromRaw(requiredText(args, "role", 32, "目标角色"));
                if (Objects.equals(actor.getUserId(), user.getId())) {
                    throw new IllegalArgumentException("不能修改自己的角色");
                }
                if (newRole.getCode().equalsIgnoreCase(user.getRole())) {
                    throw new IllegalArgumentException("用户「" + user.getUsername() + "」已经是「" + newRole.getName() + "」");
                }
                targetName = user.getUsername();
                payload.put("userId", user.getId());
                payload.put("username", targetName);
                payload.put("role", newRole.getCode());
                title = "修改用户角色：" + targetName + " → " + newRole.getName();
                pb.addField("用户", targetName)
                        .addDiff("角色", user.getRoleName(), newRole.getName())
                        .addImpact("该用户现有登录会话将失效并需重新登录，新权限立即生效")
                        .addImpact("名下活跃凭证 " + nvl(user.getKeyCount()) + " 个、接入终端 " + nvl(user.getClientCount()) + " 个")
                        .confirmTargetName(targetName);
            }
            case "toggle_status" -> {
                UserSummaryDto user = resolveUser(usernameRef);
                String targetStatus = requiredText(args, "status", 16, "目标状态").toUpperCase(Locale.ROOT);
                if (!Set.of(UserStatus.ACTIVE, UserStatus.DISABLED).contains(targetStatus)) {
                    throw new IllegalArgumentException("状态仅支持 ACTIVE（启用）或 DISABLED（停用）");
                }
                if (UserStatus.DISABLED.equals(targetStatus) && Objects.equals(actor.getUserId(), user.getId())) {
                    throw new IllegalArgumentException("不能停用当前登录的自己账号");
                }
                targetName = user.getUsername();
                payload.put("userId", user.getId());
                payload.put("username", targetName);
                payload.put("status", targetStatus);
                boolean disabling = UserStatus.DISABLED.equals(targetStatus);
                title = (disabling ? "停用用户账号：" : "启用用户账号：") + targetName;
                pb.addField("用户", targetName)
                        .addDiff("状态", user.getStatus(), targetStatus);
                if (disabling) {
                    pb.addImpact("停用后该用户立即被踢出登录，其名下 " + nvl(user.getKeyCount()) + " 个开放 API 凭证与 "
                            + nvl(user.getClientCount()) + " 个接入终端将全部拒绝调用");
                }
                pb.confirmTargetName(targetName);
            }
            case "reset_password" -> {
                UserSummaryDto user = resolveUser(usernameRef);
                targetName = user.getUsername();
                payload.put("userId", user.getId());
                payload.put("username", targetName);
                title = "重置用户密码：" + targetName;
                pb.addField("用户", targetName)
                        .addField("当前角色", user.getRoleName())
                        .addImpact("该用户现有会话将立即失效，下次登录须强制修改密码")
                        .secretInput("新临时密码（留空则自动生成）", "至少 8 位，留空自动生成随机临时密码", false, 8)
                        .confirmTargetName(targetName)
                        .note("新密码仅通过卡片密码框直传后端，模型与日志均不可见。");
            }
            default -> throw new IllegalArgumentException("不支持的用户管理操作：" + op);
        }
        return support.createPendingCard(ctx, MANAGE_USER, "D", title, payload, pb.build(), "USER");
    }

    Map<String, Object> draftManageGatewayChannel(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        requireSuperAdmin(actor, "管理模型网关");
        String op = requiredText(args, "operation", 32, "操作类型").toLowerCase(Locale.ROOT);
        String target = requiredText(args, "target", 100, "目标通道或配置");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", op);
        var pb = previewBuilder();
        String title;
        String targetName;

        switch (op) {
            case "update_llm_key" -> {
                LlmProviderView p = resolveProvider(target);
                targetName = p.getName();
                payload.put("providerId", p.getId());
                payload.put("providerName", targetName);
                title = "更换模型通道密钥：" + targetName;
                pb.addField("通道名称", targetName)
                        .addField("默认模型", blankOr(p.getDefaultModel(), "-"))
                        .addImpacts(describeProviderImpacts(p))
                        .secretInput("新 API Key", "请输入新的 API Key（直传后端加密保存）", true, 4)
                        .confirmTargetName(targetName)
                        .note("新密钥仅由卡片密码框提交至网关加密落库，绝不经过 AI 模型或写入对话与审计日志。");
            }
            case "toggle_llm_channel" -> {
                LlmProviderView p = resolveProvider(target);
                Boolean enabled = bool(args, "enabled");
                if (enabled == null) throw new IllegalArgumentException("请指明 enabled（true 启用 / false 停用）");
                targetName = p.getName();
                payload.put("providerId", p.getId());
                payload.put("providerName", targetName);
                payload.put("enabled", enabled);
                title = (enabled ? "启用模型通道：" : "停用模型通道：") + targetName;
                pb.addField("通道名称", targetName)
                        .addDiff("调度状态", p.isEnabled() ? "已启用" : "已停用", enabled ? "已启用" : "已停用")
                        .addImpacts(describeProviderImpacts(p))
                        .confirmTargetName(targetName);
            }
            case "create_llm_channel" -> {
                String baseUrl = requiredText(args, "baseUrl", 500, "接口 Base URL");
                if (urlValidator != null) {
                    urlValidator.validateProviderBaseUrl(baseUrl);
                }
                String defaultModel = requiredText(args, "defaultModel", 120, "默认模型");
                String protocol = text(args, "protocol");
                protocol = protocol == null ? "OPENAI" : protocol.toUpperCase(Locale.ROOT);
                targetName = target;
                payload.put("name", targetName);
                payload.put("baseUrl", baseUrl);
                payload.put("defaultModel", defaultModel);
                payload.put("protocol", protocol);
                title = "新增大模型通道：" + targetName;
                pb.addField("通道名称", targetName)
                        .addField("协议", protocol)
                        .addField("接口地址", baseUrl)
                        .addField("默认模型", defaultModel)
                        .addImpact("新增通道将加入模型网关调度列表")
                        .secretInput("通道 API Key（无 Key 通道可留空）", "sk-...（直传后端加密保存，不经过模型）", false, 0)
                        .confirmTargetName(targetName);
            }
            case "activate_embedding" -> {
                EmbeddingConfigView cfg = embeddingConfigService.listViews().stream()
                        .filter(e -> target.equalsIgnoreCase(e.getId()) || target.equalsIgnoreCase(e.getName())
                                || target.equalsIgnoreCase(e.getModelName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未找到向量模型配置「" + target + "」"));
                targetName = cfg.getName();
                payload.put("configId", cfg.getId());
                payload.put("configName", targetName);
                title = "切换激活向量模型：" + targetName;
                pb.addField("目标向量模型", targetName)
                        .addField("模型标识", cfg.getModelName())
                        .addImpact("切换全局激活向量模型会影响全部自建知识库的向量检索，若维度或模型不同需重建索引")
                        .confirmTargetName(targetName);
            }
            case "activate_rerank" -> {
                requireBean(rerankConfigService, "Rerank 模型模块");
                RerankConfigView cfg = rerankConfigService.listViews().stream()
                        .filter(r -> target.equalsIgnoreCase(r.getId()) || target.equalsIgnoreCase(r.getName())
                                || target.equalsIgnoreCase(r.getModelName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未找到 Rerank 模型配置「" + target + "」"));
                targetName = cfg.getName();
                payload.put("configId", cfg.getId());
                payload.put("configName", targetName);
                title = "切换激活 Rerank 模型：" + targetName;
                pb.addField("目标 Rerank 模型", targetName)
                        .addField("模型标识", cfg.getModelName())
                        .addImpact("切换后将影响所有启用重排模型的知识库检索排序")
                        .confirmTargetName(targetName);
            }
            case "update_dify_key" -> {
                requireBean(difyConfigService, "Dify 知识引擎模块");
                DifyConfigView cfg = difyConfigService.listViews().stream()
                        .filter(d -> target.equalsIgnoreCase(d.getId()) || target.equalsIgnoreCase(d.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未找到 Dify 引擎配置「" + target + "」"));
                targetName = cfg.getName();
                payload.put("configId", cfg.getId());
                payload.put("configName", targetName);
                title = "更换 Dify 引擎密钥：" + targetName;
                pb.addField("Dify 配置名称", targetName)
                        .addImpact("更换后将影响所有 Dify 外部知识库的文档同步与检索")
                        .secretInput("新 Dify Dataset API Key", "dataset-...（直传后端加密保存）", true, 4)
                        .confirmTargetName(targetName);
            }
            default -> throw new IllegalArgumentException("不支持的模型网关操作：" + op);
        }
        return support.createPendingCard(ctx, MANAGE_GATEWAY_CHANNEL, "D", title, payload, pb.build(), "MODEL_GATEWAY");
    }

    Map<String, Object> draftUpdateGuardrailPolicy(JsonNode args, ToolContext ctx) {
        requireBean(securityPlatformService, "护栏策略模块");
        CurrentActor actor = ctx.actor();
        GuardrailPolicy current = securityPlatformService.loadPolicy(actor);
        boolean isGlobal = "GLOBAL".equalsIgnoreCase(current.getOwnerId()) || actor.isSuperAdmin();
        String targetName = isGlobal ? "全局护栏策略" : actor.getUsername() + "护栏策略";

        Map<String, Object> payload = new LinkedHashMap<>();
        var pb = previewBuilder().addField("作用范围", isGlobal ? "全局默认护栏策略" : "当前账号护栏策略");
        int changes = 0;

        Boolean killSwitch = bool(args, "killSwitch");
        if (killSwitch != null && !killSwitch.equals(current.getKillSwitch())) {
            payload.put("killSwitch", killSwitch);
            pb.addDiff("紧急停用 (killSwitch)", current.getKillSwitch() ? "开启" : "关闭", killSwitch ? "开启" : "关闭");
            if (killSwitch) {
                pb.addImpact("开启紧急停用后，范围内所有开放 API 对话请求将被立即拦截！");
            }
            changes++;
        }
        Boolean piiMask = bool(args, "piiMask");
        if (piiMask != null && !piiMask.equals(current.getPiiMask())) {
            payload.put("piiMask", piiMask);
            pb.addDiff("PII 脱敏", current.getPiiMask() ? "开启" : "关闭", piiMask ? "开启" : "关闭");
            changes++;
        }
        String sensitiveAction = text(args, "sensitiveAction");
        if (sensitiveAction != null) {
            String norm = sensitiveAction.toUpperCase(Locale.ROOT);
            if (!Set.of("BLOCK", "MASK", "LOG").contains(norm)) throw new IllegalArgumentException("sensitiveAction 仅支持 BLOCK / MASK / LOG");
            if (!norm.equalsIgnoreCase(current.getSensitiveAction())) {
                payload.put("sensitiveAction", norm);
                pb.addDiff("敏感词处置", current.getSensitiveAction(), norm);
                changes++;
            }
        }
        String promptInjection = text(args, "promptInjection");
        if (promptInjection != null) {
            String norm = promptInjection.toUpperCase(Locale.ROOT);
            if (!Set.of("BLOCK", "LOG", "OFF").contains(norm)) throw new IllegalArgumentException("promptInjection 仅支持 BLOCK / LOG / OFF");
            if (!norm.equalsIgnoreCase(current.getPromptInjection())) {
                payload.put("promptInjection", norm);
                pb.addDiff("注入检测", current.getPromptInjection(), norm);
                changes++;
            }
        }
        Boolean outputGuard = bool(args, "outputGuard");
        if (outputGuard != null && !outputGuard.equals(current.getOutputGuard())) {
            payload.put("outputGuard", outputGuard);
            pb.addDiff("输出护栏", current.getOutputGuard() ? "开启" : "关闭", outputGuard ? "开启" : "关闭");
            changes++;
        }
        String clientPolicy = text(args, "clientPolicy");
        if (clientPolicy != null) {
            String norm = clientPolicy.toUpperCase(Locale.ROOT);
            if (!Set.of("OFF", "LOG", "STRICT").contains(norm)) throw new IllegalArgumentException("clientPolicy 仅支持 OFF / LOG / STRICT");
            if (!norm.equalsIgnoreCase(current.getClientPolicy())) {
                payload.put("clientPolicy", norm);
                pb.addDiff("终端管控模式", current.getClientPolicy(), norm);
                if ("STRICT".equals(norm)) {
                    pb.addImpact("严格模式下未审批终端的开放接口请求将被直接拒绝");
                }
                changes++;
            }
        }
        Double rpmNum = number(args, "defaultRpm");
        if (rpmNum != null) {
            int rpm = rpmNum.intValue();
            if (rpm < 1 || rpm > 100000) throw new IllegalArgumentException("defaultRpm 须在 1~100000 之间");
            if (!Objects.equals(rpm, current.getDefaultRpm())) {
                payload.put("defaultRpm", rpm);
                pb.addDiff("默认限流 (RPM)", String.valueOf(current.getDefaultRpm()), String.valueOf(rpm));
                changes++;
            }
        }
        Double dailyNum = number(args, "defaultDailyTokens");
        if (dailyNum != null) {
            long daily = dailyNum.longValue();
            if (daily < 0) throw new IllegalArgumentException("每日 Token 配额不能为负数");
            if (!Objects.equals(daily, current.getDefaultDailyTokens())) {
                payload.put("defaultDailyTokens", daily);
                pb.addDiff("每日 Token 配额", String.valueOf(current.getDefaultDailyTokens()), String.valueOf(daily));
                changes++;
            }
        }
        Double maxInNum = number(args, "maxInputChars");
        if (maxInNum != null) {
            int maxIn = maxInNum.intValue();
            if (maxIn < 100 || maxIn > 100000) throw new IllegalArgumentException("maxInputChars 须在 100~100000 之间");
            if (!Objects.equals(maxIn, current.getMaxInputChars())) {
                payload.put("maxInputChars", maxIn);
                pb.addDiff("最大输入字数", String.valueOf(current.getMaxInputChars()), String.valueOf(maxIn));
                changes++;
            }
        }
        Double maxTurnsNum = number(args, "maxHistoryTurns");
        if (maxTurnsNum != null) {
            int turns = maxTurnsNum.intValue();
            if (turns < 0 || turns > 100) throw new IllegalArgumentException("maxHistoryTurns 须在 0~100 之间");
            if (!Objects.equals(turns, current.getMaxHistoryTurns())) {
                payload.put("maxHistoryTurns", turns);
                pb.addDiff("最大历史轮数", String.valueOf(current.getMaxHistoryTurns()), String.valueOf(turns));
                changes++;
            }
        }
        if (changes == 0) {
            throw new IllegalArgumentException("未检测到任何护栏策略变更，无需修改");
        }
        pb.addImpact("护栏策略修改后将立即对范围内所有对话与开放接口生效")
                .confirmTargetName(targetName);
        return support.createPendingCard(ctx, UPDATE_GUARDRAIL_POLICY, "D",
                "修改护栏策略：" + targetName, payload, pb.build(), "GUARDRAIL_POLICY");
    }

    Map<String, Object> draftRevokeApiKey(JsonNode args, ToolContext ctx) {
        CurrentActor actor = ctx.actor();
        String ref = requiredText(args, "key", 100, "开放凭证");
        String op = requiredText(args, "operation", 16, "操作类型").toLowerCase(Locale.ROOT);
        if (!Set.of("disable", "rotate", "revoke").contains(op)) {
            throw new IllegalArgumentException("操作仅支持 disable（停用）、rotate（轮换）或 revoke（永久吊销）");
        }
        OpenApiKey key = openApiKeyService.listForActor(actor).stream()
                .filter(k -> ref.equalsIgnoreCase(k.getId()) || ref.equalsIgnoreCase(k.getName()) || ref.equalsIgnoreCase(k.getKeyPrefix()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到开放凭证「" + ref + "」（或当前账号无权操作）"));

        Double grace = number(args, "graceHours");
        int graceHours = grace == null ? 24 : Math.max(0, Math.min(168, grace.intValue()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyId", key.getId());
        payload.put("keyName", key.getName());
        payload.put("operation", op);
        payload.put("graceHours", graceHours);

        String opLabel = switch (op) {
            case "disable" -> "停用开放凭证";
            case "rotate" -> "轮换开放凭证";
            default -> "永久吊销开放凭证";
        };
        var pb = previewBuilder()
                .addField("凭证名称", key.getName())
                .addField("凭证前缀", key.getKeyPrefix())
                .addField("当前状态", key.getStatus())
                .addField("操作", opLabel);
        if ("rotate".equals(op)) {
            pb.addField("旧密钥宽限期", graceHours + " 小时")
                    .addImpact("轮换后将生成新密钥（仅显示一次），旧密钥在 " + graceHours + " 小时后失效");
        } else {
            pb.addImpact("使用该凭证（前缀 " + key.getKeyPrefix() + "）调用开放 API 的业务系统将立即返回 401 鉴权失败");
        }
        pb.confirmTargetName(key.getName());
        return support.createPendingCard(ctx, REVOKE_API_KEY, "D",
                opLabel + "：" + key.getName(), payload, pb.build(), "API_KEY");
    }

    // ==================== 确认后执行 + 回读 ====================

    public AssistantWriteTools.ExecutionResult execute(String toolName,
                                                       String payloadJson,
                                                       AssistantActionConfirmRequest confirmRequest,
                                                       CurrentActor actor) {
        JsonNode p;
        try {
            p = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("操作参数已损坏，无法执行");
        }
        return switch (toolName) {
            case CREATE_HTTP_TOOL -> executeCreateHttpTool(p);
            case CREATE_SKILL -> executeCreateSkill(p, actor);
            case SAVE_AGENT_AS_TEMPLATE -> executeSaveTemplate(p, actor);
            case REGISTER_MCP_SERVER -> executeRegisterMcpServer(p, actor);
            case UPDATE_AGENT_SETTINGS -> executeUpdateAgentSettings(p, actor);
            case UPDATE_KB_SETTINGS -> executeUpdateKbSettings(p, actor);
            case UPDATE_FAQ -> executeUpdateFaq(p, actor);
            case ROLLBACK_AGENT_PROMPT -> executeRollbackAgentPrompt(p, actor);
            case REBUILD_KB_INDEX -> executeRebuildKbIndex(p, actor);
            case RUN_RAG_EVAL -> executeRunRagEval(p, actor);
            case REFRESH_MCP_SERVER -> executeRefreshMcpServer(p, actor);
            case DELETE_RESOURCE -> executeDeleteResource(p, actor);
            case MANAGE_USER -> executeManageUser(p, confirmRequest, actor);
            case MANAGE_GATEWAY_CHANNEL -> executeManageGatewayChannel(p, confirmRequest, actor);
            case UPDATE_GUARDRAIL_POLICY -> executeUpdateGuardrailPolicy(p, actor);
            case REVOKE_API_KEY -> executeRevokeApiKey(p, actor);
            case DISABLE_SHARE_LINK -> executeDisableShareLink(p, actor);
            default -> throw new IllegalStateException("不支持的扩展写操作：" + toolName);
        };
    }

    private AssistantWriteTools.ExecutionResult executeCreateHttpTool(JsonNode p) {
        requireBean(platformToolService, "工具管理模块");
        PlatformToolUpdateRequest req = new PlatformToolUpdateRequest();
        req.setName(p.path("name").asText());
        req.setTitle(p.path("title").asText());
        req.setDescription(p.path("description").asText());
        req.setEnabled(true);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", p.path("url").asText());
        cfg.put("method", p.path("method").asText("POST"));
        cfg.put("timeoutSeconds", p.path("timeoutSeconds").asInt(8));
        req.setConfig(cfg);
        PlatformToolView created = platformToolService.create(req);
        String readback = "工具 code=" + created.getCode() + "，状态=" + (Boolean.TRUE.equals(created.getEnabled()) ? "已启用" : "停用");
        return new AssistantWriteTools.ExecutionResult("HTTP_TOOL", created.getId(),
                "已创建自定义 HTTP 工具「" + created.getTitle() + "」", "查看工具", "/dashboard?tab=tools", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeCreateSkill(JsonNode p, CurrentActor actor) {
        requireBean(skillService, "技能库模块");
        SkillRequest req = new SkillRequest();
        req.setName(p.path("name").asText());
        req.setDescription(p.path("description").asText());
        req.setContent(p.path("content").asText());
        List<String> reqTools = new ArrayList<>();
        p.path("requiredTools").forEach(n -> reqTools.add(n.asText()));
        req.setRequiredTools(reqTools);
        req.setEnabled(true);
        req.setChangeNote("AI 助手创建");
        SkillView created = skillService.create(req, actor);
        String readback = "技能 ID=" + created.id() + "，版本 v" + created.version() + "，状态=" + (created.enabled() ? "已启用" : "停用");
        return new AssistantWriteTools.ExecutionResult("SKILL", created.id(),
                "已创建技能「" + created.name() + "」", "查看技能库", "/dashboard?tab=skills", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeSaveTemplate(JsonNode p, CurrentActor actor) {
        AgentTemplate tpl = new AgentTemplate();
        tpl.setName(p.path("name").asText());
        tpl.setCategory(p.path("category").asText("通用智能"));
        tpl.setDescription(p.hasNonNull("description") ? p.get("description").asText() : null);
        tpl.setSystemPrompt(p.path("systemPrompt").asText());
        if (p.hasNonNull("modelName")) tpl.setModelName(p.get("modelName").asText());
        if (p.hasNonNull("temperature")) tpl.setTemperature(p.get("temperature").asDouble());
        if (p.hasNonNull("maxTokens")) tpl.setMaxTokens(p.get("maxTokens").asInt());
        AgentTemplate created = templateService.create(tpl, actor);
        String readback = "模板 ID=" + created.getId() + "，分类=" + created.getCategory();
        return new AssistantWriteTools.ExecutionResult("TEMPLATE", created.getId(),
                "已保存场景模板「" + created.getName() + "」", "查看场景模板", "/dashboard?tab=templates", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeRegisterMcpServer(JsonNode p, CurrentActor actor) {
        requireSuperAdmin(actor, "登记 MCP 服务");
        requireBean(mcpServerService, "MCP 服务模块");
        McpServerRequest req = new McpServerRequest();
        req.setName(p.path("name").asText());
        req.setUrl(p.path("url").asText());
        req.setTransport(p.path("transport").asText("STREAMABLE_HTTP"));
        if (p.hasNonNull("slug")) req.setSlug(p.get("slug").asText());
        if (p.hasNonNull("remark")) req.setRemark(p.get("remark").asText());
        req.setEnabled(true);
        McpServerView created = mcpServerService.create(req);
        String readback = "MCP 服务 ID=" + created.getId() + "，slug=" + created.getSlug() + "，待审核工具数=" + created.getPendingToolCount();
        return new AssistantWriteTools.ExecutionResult("MCP_SERVER", created.getId(),
                "已登记 MCP 服务「" + created.getName() + "」", "查看 MCP 服务", "/dashboard?tab=tools", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeUpdateAgentSettings(JsonNode p, CurrentActor actor) {
        Agent current = loadManageableAgent(p.path("agentId").asText(), actor);
        String baseline = p.path("baselineSha256").asText("");
        if (!baseline.isBlank() && !baseline.equalsIgnoreCase(agentSettingsFingerprint(current))) {
            throw new IllegalStateException("生成卡片后该智能体配置已被他人修改，为避免覆盖，请让助手重新生成修改卡片");
        }
        Agent patch = AssistantWriteTools.patchFrom(current);
        if (p.has("primaryProviderId")) patch.setPrimaryProviderId(p.get("primaryProviderId").asText());
        if (p.has("fallbackProviderId")) patch.setFallbackProviderId(p.get("fallbackProviderId").asText());
        if (p.hasNonNull("modelName")) patch.setModelName(p.get("modelName").asText());
        if (p.hasNonNull("temperature")) patch.setTemperature(p.get("temperature").asDouble());
        if (p.hasNonNull("topP")) patch.setTopP(p.get("topP").asDouble());
        if (p.hasNonNull("maxTokens")) patch.setMaxTokens(p.get("maxTokens").asInt());
        if (p.hasNonNull("memoryTurns")) patch.setMemoryTurns(p.get("memoryTurns").asInt());
        if (p.hasNonNull("thinkingMode")) patch.setThinkingMode(p.get("thinkingMode").asText());
        if (p.hasNonNull("knowledgeMode")) patch.setKnowledgeMode(p.get("knowledgeMode").asText());
        if (p.has("skillIds") && p.get("skillIds").isArray()) {
            List<String> ids = new ArrayList<>();
            p.get("skillIds").forEach(n -> ids.add(n.asText()));
            patch.setSkillIds(ids);
        }
        if (p.hasNonNull("toolsConfig")) patch.setToolsConfig(p.get("toolsConfig").asText());

        Agent saved = agentService.update(current.getId(), patch, actor);
        Agent readbackAgent = agentService.getById(current.getId(), actor).orElse(saved);
        String channelLabel = resolveProviderNameOrDefault(readbackAgent.getPrimaryProviderId());
        String readback = "主通道=" + channelLabel
                + "，模型=" + blankOr(readbackAgent.getModelName(), "跟随默认")
                + "，温度=" + readbackAgent.getTemperature()
                + "，记忆=" + readbackAgent.resolveMemoryTurns() + "轮"
                + "，思考=" + Agent.normalizeThinkingMode(readbackAgent.getThinkingMode())
                + "，知识库模式=" + Agent.normalizeKnowledgeMode(readbackAgent.getKnowledgeMode());
        return new AssistantWriteTools.ExecutionResult("AGENT", readbackAgent.getId(),
                "已更新智能体「" + readbackAgent.getName() + "」配置", "去调试",
                AssistantQueryTools.agentLink(readbackAgent.getId()), null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeUpdateKbSettings(JsonNode p, CurrentActor actor) {
        String kbId = p.path("kbId").asText();
        KnowledgeBase current = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new IllegalStateException("知识库已不存在"));
        String baseline = p.path("baselineSha256").asText("");
        if (!baseline.isBlank() && !baseline.equalsIgnoreCase(kbSettingsFingerprint(current))) {
            throw new IllegalStateException("生成卡片后该知识库设置已被修改，为避免覆盖，请让助手重新生成修改卡片");
        }
        UpdateKnowledgeBaseRequest req = new UpdateKnowledgeBaseRequest();
        if (p.hasNonNull("searchMethod")) req.setSearchMethod(p.get("searchMethod").asText());
        if (p.hasNonNull("topK")) req.setTopK(p.get("topK").asInt());
        if (p.hasNonNull("scoreThreshold")) req.setScoreThreshold(p.get("scoreThreshold").asDouble());
        if (p.hasNonNull("vectorWeight")) req.setVectorWeight(p.get("vectorWeight").asDouble());
        if (p.hasNonNull("keywordWeight")) req.setKeywordWeight(p.get("keywordWeight").asDouble());
        if (p.hasNonNull("rerankEnabled")) req.setRerankEnabled(p.get("rerankEnabled").asBoolean());
        if (p.hasNonNull("rerankMode")) req.setRerankMode(p.get("rerankMode").asText());

        KnowledgeBase updated = knowledgeBaseService.updateKnowledgeBase(kbId, req, actor);
        String readback = "检索=" + updated.getSearchMethod() + "，TopK=" + updated.getTopK()
                + "，阈值=" + updated.getScoreThreshold()
                + "，Rerank=" + (Boolean.TRUE.equals(updated.getRerankEnabled()) ? "开" : "关");
        return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_BASE", updated.getId(),
                "已更新知识库「" + updated.getName() + "」检索设置", "查看知识库",
                AssistantQueryTools.knowledgeBaseLink(updated.getId()), null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeUpdateFaq(JsonNode p, CurrentActor actor) {
        String kbId = p.path("kbId").asText();
        String faqId = p.path("faqId").asText();
        KnowledgeFaq current = resolveFaq(kbId, faqId, actor);
        String baseline = p.path("baselineSha256").asText("");
        String nowFp = faqFingerprint(current);
        if (!baseline.isBlank() && !baseline.equalsIgnoreCase(nowFp)) {
            throw new IllegalStateException("生成卡片后该 FAQ 已被修改，请让助手重新生成修改卡片");
        }
        UpdateFaqRequest req = new UpdateFaqRequest();
        if (p.hasNonNull("question")) req.setQuestion(p.get("question").asText());
        if (p.hasNonNull("answer")) req.setAnswer(p.get("answer").asText());
        if (p.hasNonNull("category")) req.setCategory(p.get("category").asText());
        if (p.hasNonNull("enabled")) req.setEnabled(p.get("enabled").asBoolean());
        if (p.has("imageUrls") && p.get("imageUrls").isArray()) {
            req.setImageUrls(AssistantWriteTools.parseMediaUrls(p, "imageUrls", KnowledgeBaseService.MAX_FAQ_IMAGES, "图片"));
        }
        if (p.has("videoUrls") && p.get("videoUrls").isArray()) {
            req.setVideoUrls(AssistantWriteTools.parseMediaUrls(p, "videoUrls", KnowledgeBaseService.MAX_FAQ_VIDEOS, "视频"));
        }
        KnowledgeFaq updated = knowledgeBaseService.updateFaq(kbId, faqId, req, actor);
        int imgCount = parseJsonStringList(updated.getImageUrls()).size();
        int vidCount = parseJsonStringList(updated.getVideoUrls()).size();
        String readback = "FAQ ID=" + updated.getId() + "，分类=" + updated.getCategory()
                + "，附图=" + imgCount + "张，视频=" + vidCount + "个"
                + "，状态=" + (!Boolean.FALSE.equals(updated.getEnabled()) ? "启用" : "停用");
        return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_BASE", kbId,
                "已更新 FAQ（" + updated.getId() + "）", "查看知识库",
                AssistantQueryTools.knowledgeBaseLink(kbId), null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeRollbackAgentPrompt(JsonNode p, CurrentActor actor) {
        Agent current = loadManageableAgent(p.path("agentId").asText(), actor);
        String baseline = p.path("baselineSha256").asText("");
        String nowPrompt = current.getSystemPrompt() == null ? "" : current.getSystemPrompt();
        if (!baseline.isBlank() && !baseline.equalsIgnoreCase(sha256(nowPrompt))) {
            throw new IllegalStateException("生成卡片后该智能体提示词已被修改，为避免覆盖，请让助手重新生成回滚卡片");
        }
        Agent patch = AssistantWriteTools.patchFrom(current);
        patch.setSystemPrompt(p.path("systemPrompt").asText());
        String vLabel = p.hasNonNull("versionNo") ? "v" + p.get("versionNo").asText() : p.path("versionId").asText();
        patch.setPromptChangeNote("AI 助手回滚至 " + vLabel);
        agentService.update(current.getId(), patch, actor, AgentPromptVersionService.SOURCE_ROLLBACK);
        return new AssistantWriteTools.ExecutionResult("AGENT", current.getId(),
                "已将「" + current.getName() + "」提示词回滚至 " + vLabel, "去调试",
                AssistantQueryTools.agentLink(current.getId()), null, "已回滚提示词至 " + vLabel);
    }

    private AssistantWriteTools.ExecutionResult executeRebuildKbIndex(JsonNode p, CurrentActor actor) {
        requireBean(rebuildService, "索引重建模块");
        String kbId = p.path("kbId").asText();
        rebuildService.start(kbId, actor);
        return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_BASE", kbId,
                "已启动知识库「" + p.path("kbName").asText(kbId) + "」后台索引重建任务", "查看进度",
                AssistantQueryTools.knowledgeBaseLink(kbId), null, "索引重建已在后台运行");
    }

    private AssistantWriteTools.ExecutionResult executeRunRagEval(JsonNode p, CurrentActor actor) {
        requireBean(ragEvaluationService, "检索评测模块");
        String kbId = p.path("kbId").asText();
        RagEvalRunRequest req = new RagEvalRunRequest();
        if (p.hasNonNull("label")) req.setLabel(p.get("label").asText());
        RagEvaluationService.RunView run = ragEvaluationService.startRun(kbId, req, actor);
        String readback = "评测运行 ID=" + run.id() + "，题目数=" + run.caseCount() + "，状态=" + run.status();
        return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_BASE", kbId,
                "已启动检索评测（共 " + run.caseCount() + " 道题）", "查看评测",
                AssistantQueryTools.knowledgeBaseLink(kbId), null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeRefreshMcpServer(JsonNode p, CurrentActor actor) {
        requireSuperAdmin(actor, "刷新 MCP 工具列表");
        requireBean(mcpServerService, "MCP 服务模块");
        McpServerView refreshed = mcpServerService.refreshTools(p.path("serverId").asText());
        String readback = "工具总数=" + refreshed.getToolCount() + "，已启用=" + refreshed.getEnabledToolCount()
                + "，待审核=" + refreshed.getPendingToolCount();
        return new AssistantWriteTools.ExecutionResult("MCP_SERVER", refreshed.getId(),
                "已刷新 MCP 服务「" + refreshed.getName() + "」工具列表（共 " + refreshed.getToolCount() + " 个工具）",
                "查看 MCP 服务", "/dashboard?tab=tools", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeDeleteResource(JsonNode p, CurrentActor actor) {
        String type = p.path("type").asText();
        String id = p.path("id").asText();
        String name = p.path("name").asText(id);
        switch (type) {
            case "agent" -> {
                if (!agentService.delete(id, actor)) {
                    throw new IllegalStateException("智能体已不存在或无法删除");
                }
                return new AssistantWriteTools.ExecutionResult("AGENT", id, "已永久删除智能体「" + name + "」", null, null, null, "已删除智能体 " + name);
            }
            case "knowledge_base" -> {
                knowledgeBaseService.deleteKnowledgeBase(id, actor);
                return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_BASE", id, "已永久删除知识库「" + name + "」", null, null, null, "已删除知识库 " + name);
            }
            case "document" -> {
                String kbId = p.path("parentId").asText();
                knowledgeBaseService.deleteDocument(kbId, id, actor);
                return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_DOCUMENT", id, "已删除文档「" + name + "」", "查看知识库",
                        AssistantQueryTools.knowledgeBaseLink(kbId), null, "已删除文档 " + name);
            }
            case "faq" -> {
                String kbId = p.path("parentId").asText();
                knowledgeBaseService.deleteFaq(kbId, id, actor);
                return new AssistantWriteTools.ExecutionResult("KNOWLEDGE_FAQ", id, "已删除 FAQ「" + name + "」", "查看知识库",
                        AssistantQueryTools.knowledgeBaseLink(kbId), null, "已删除 FAQ");
            }
            case "http_tool" -> {
                requireBean(platformToolService, "工具管理模块");
                platformToolService.delete(id);
                return new AssistantWriteTools.ExecutionResult("HTTP_TOOL", id, "已删除自定义 HTTP 工具「" + name + "」", null, null, null, "已删除工具 " + name);
            }
            case "skill" -> {
                requireBean(skillService, "技能库模块");
                skillService.delete(id, actor);
                return new AssistantWriteTools.ExecutionResult("SKILL", id, "已删除技能「" + name + "」", null, null, null, "已删除技能 " + name);
            }
            case "template" -> {
                if (!templateService.delete(id, actor)) {
                    throw new IllegalStateException("场景模板已不存在或无权删除");
                }
                return new AssistantWriteTools.ExecutionResult("TEMPLATE", id, "已删除场景模板「" + name + "」", null, null, null, "已删除模板 " + name);
            }
            case "mcp_server" -> {
                requireSuperAdmin(actor, "删除 MCP 服务");
                requireBean(mcpServerService, "MCP 服务模块");
                mcpServerService.delete(id);
                return new AssistantWriteTools.ExecutionResult("MCP_SERVER", id, "已删除 MCP 服务「" + name + "」", null, null, null, "已删除 MCP 服务 " + name);
            }
            default -> throw new IllegalStateException("不支持删除的类型：" + type);
        }
    }

    private AssistantWriteTools.ExecutionResult executeManageUser(JsonNode p, AssistantActionConfirmRequest req, CurrentActor actor) {
        requireSuperAdmin(actor, "管理用户与角色");
        requireBean(userAdminService, "用户管理模块");
        String op = p.path("operation").asText();
        String secretValue = req != null && req.secretValue() != null && !req.secretValue().isBlank() ? req.secretValue().trim() : null;

        switch (op) {
            case "create_user" -> {
                CreateUserRequest createReq = new CreateUserRequest();
                createReq.setUsername(p.path("username").asText());
                createReq.setNickname(p.path("nickname").asText());
                createReq.setRole(p.path("role").asText());
                if (secretValue != null) {
                    createReq.setPassword(secretValue);
                }
                Map<String, Object> created = userAdminService.createUser(createReq);
                String tempPwd = secretValue == null && created.get("tempPassword") != null
                        ? String.valueOf(created.get("tempPassword")) : null;
                String userId = extractUserId(created);
                return new AssistantWriteTools.ExecutionResult("USER", userId,
                        "已创建用户「" + createReq.getUsername() + "」", "查看用户", "/dashboard?tab=users",
                        tempPwd, "用户 " + createReq.getUsername() + " 已创建，角色=" + createReq.getRole());
            }
            case "change_role" -> {
                UserSummaryDto updated = userAdminService.changeUserRole(p.path("userId").asText(), p.path("role").asText());
                return new AssistantWriteTools.ExecutionResult("USER", updated.getId(),
                        "已将用户「" + updated.getUsername() + "」角色修改为「" + updated.getRoleName() + "」",
                        "查看用户", "/dashboard?tab=users", null, "用户角色已生效为 " + updated.getRoleName());
            }
            case "toggle_status" -> {
                UserSummaryDto updated = userAdminService.changeUserStatus(p.path("userId").asText(), p.path("status").asText());
                return new AssistantWriteTools.ExecutionResult("USER", updated.getId(),
                        "已将用户「" + updated.getUsername() + "」状态更新为 " + updated.getStatus(),
                        "查看用户", "/dashboard?tab=users", null, "用户状态=" + updated.getStatus());
            }
            case "reset_password" -> {
                Map<String, Object> res = userAdminService.resetPassword(p.path("userId").asText(), secretValue, true);
                String tempPwd = secretValue == null && res.get("tempPassword") != null
                        ? String.valueOf(res.get("tempPassword")) : null;
                return new AssistantWriteTools.ExecutionResult("USER", p.path("userId").asText(),
                        "已重置用户「" + p.path("username").asText() + "」密码（下次登录需修改）",
                        "查看用户", "/dashboard?tab=users", tempPwd, "密码已重置");
            }
            default -> throw new IllegalStateException("不支持的用户管理操作：" + op);
        }
    }

    private AssistantWriteTools.ExecutionResult executeManageGatewayChannel(JsonNode p, AssistantActionConfirmRequest req, CurrentActor actor) {
        requireSuperAdmin(actor, "管理模型网关");
        String op = p.path("operation").asText();
        String secretValue = req != null && req.secretValue() != null && !req.secretValue().isBlank() ? req.secretValue().trim() : null;

        switch (op) {
            case "update_llm_key" -> {
                if (secretValue == null) throw new IllegalArgumentException("新 API Key 不能为空");
                LlmProviderRequest upd = new LlmProviderRequest();
                upd.setApiKey(secretValue);
                LlmProviderView saved = gatewayService.update(p.path("providerId").asText(), upd);
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", saved.getId(),
                        "已更新模型通道「" + saved.getName() + "」API Key（已加密保存）",
                        "查看模型网关", "/dashboard?tab=gateway-llm", null, "通道密钥已更新");
            }
            case "toggle_llm_channel" -> {
                boolean enabled = p.path("enabled").asBoolean();
                LlmProviderView saved = gatewayService.toggleEnabled(p.path("providerId").asText(), enabled);
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", saved.getId(),
                        "已" + (enabled ? "启用" : "停用") + "模型通道「" + saved.getName() + "」",
                        "查看模型网关", "/dashboard?tab=gateway-llm", null, "通道状态=" + (saved.isEnabled() ? "已启用" : "已停用"));
            }
            case "create_llm_channel" -> {
                LlmProviderRequest createReq = new LlmProviderRequest();
                createReq.setVendor(LlmProviderType.CUSTOM);
                createReq.setProtocol(LlmProtocolType.valueOf(p.path("protocol").asText("OPENAI")));
                createReq.setName(p.path("name").asText());
                createReq.setBaseUrl(p.path("baseUrl").asText());
                createReq.setDefaultModel(p.path("defaultModel").asText());
                createReq.setModels(p.path("defaultModel").asText());
                createReq.setEnabled(true);
                if (secretValue != null) createReq.setApiKey(secretValue);
                LlmProviderView saved = gatewayService.create(createReq);
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", saved.getId(),
                        "已新增大模型通道「" + saved.getName() + "」", "查看模型网关", "/dashboard?tab=gateway-llm",
                        null, "通道已创建，默认模型=" + saved.getDefaultModel());
            }
            case "activate_embedding" -> {
                embeddingConfigService.activate(p.path("configId").asText());
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", p.path("configId").asText(),
                        "已切换激活向量模型「" + p.path("configName").asText() + "」",
                        "查看向量模型", "/dashboard?tab=gateway-embedding", null, "已激活向量模型 " + p.path("configName").asText());
            }
            case "activate_rerank" -> {
                requireBean(rerankConfigService, "Rerank 模型模块");
                rerankConfigService.activate(p.path("configId").asText());
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", p.path("configId").asText(),
                        "已切换激活 Rerank 模型「" + p.path("configName").asText() + "」",
                        "查看 Rerank 模型", "/dashboard?tab=gateway-rerank", null, "已激活 Rerank 模型 " + p.path("configName").asText());
            }
            case "update_dify_key" -> {
                requireBean(difyConfigService, "Dify 知识引擎模块");
                if (secretValue == null) throw new IllegalArgumentException("新 Dify API Key 不能为空");
                DifyConfigRequest dReq = new DifyConfigRequest();
                dReq.setApiKey(secretValue);
                DifyConfigView saved = difyConfigService.update(p.path("configId").asText(), dReq);
                return new AssistantWriteTools.ExecutionResult("MODEL_GATEWAY", saved.getId(),
                        "已更新 Dify 引擎「" + saved.getName() + "」API Key（已加密保存）",
                        "查看 Dify 引擎", "/dashboard?tab=gateway-dify", null, "Dify 引擎密钥已更新");
            }
            default -> throw new IllegalStateException("不支持的网关操作：" + op);
        }
    }

    private AssistantWriteTools.ExecutionResult executeUpdateGuardrailPolicy(JsonNode p, CurrentActor actor) {
        requireBean(securityPlatformService, "护栏策略模块");
        GuardrailPolicy current = securityPlatformService.loadPolicy(actor);
        GuardrailPolicy incoming = new GuardrailPolicy();
        incoming.setOwnerId(current.getOwnerId());
        incoming.setClientPolicy(p.hasNonNull("clientPolicy") ? p.get("clientPolicy").asText() : current.getClientPolicy());
        incoming.setDefaultRpm(p.hasNonNull("defaultRpm") ? p.get("defaultRpm").asInt() : current.getDefaultRpm());
        incoming.setDefaultDailyTokens(p.hasNonNull("defaultDailyTokens") ? p.get("defaultDailyTokens").asLong() : current.getDefaultDailyTokens());
        incoming.setMaxInputChars(p.hasNonNull("maxInputChars") ? p.get("maxInputChars").asInt() : current.getMaxInputChars());
        incoming.setMaxHistoryTurns(p.hasNonNull("maxHistoryTurns") ? p.get("maxHistoryTurns").asInt() : current.getMaxHistoryTurns());
        incoming.setPiiMask(p.hasNonNull("piiMask") ? p.get("piiMask").asBoolean() : current.getPiiMask());
        incoming.setSensitiveWords(new ArrayList<>(current.getSensitiveWords()));
        incoming.setSensitiveAction(p.hasNonNull("sensitiveAction") ? p.get("sensitiveAction").asText() : current.getSensitiveAction());
        incoming.setPromptInjection(p.hasNonNull("promptInjection") ? p.get("promptInjection").asText() : current.getPromptInjection());
        incoming.setOutputGuard(p.hasNonNull("outputGuard") ? p.get("outputGuard").asBoolean() : current.getOutputGuard());
        incoming.setAllowedHours(current.getAllowedHours());
        incoming.setKillSwitch(p.hasNonNull("killSwitch") ? p.get("killSwitch").asBoolean() : current.getKillSwitch());

        GuardrailPolicy saved = securityPlatformService.savePolicy(incoming, actor);
        String readback = "killSwitch=" + saved.getKillSwitch() + "，piiMask=" + saved.getPiiMask()
                + "，sensitiveAction=" + saved.getSensitiveAction() + "，clientPolicy=" + saved.getClientPolicy();
        return new AssistantWriteTools.ExecutionResult("GUARDRAIL_POLICY", saved.getOwnerId(),
                "已更新护栏策略并立即生效", "查看护栏策略", "/dashboard?tab=security", null, readback);
    }

    private AssistantWriteTools.ExecutionResult executeRevokeApiKey(JsonNode p, CurrentActor actor) {
        String keyId = p.path("keyId").asText();
        String keyName = p.path("keyName").asText(keyId);
        String op = p.path("operation").asText();
        return switch (op) {
            case "disable" -> {
                OpenApiKey updated = openApiKeyService.updateStatus(keyId, "DISABLED", actor);
                yield new AssistantWriteTools.ExecutionResult("API_KEY", updated.getId(),
                        "已停用开放 API 凭证「" + keyName + "」", "查看开放凭证", "/dashboard?tab=security",
                        null, "凭证状态=" + updated.getStatus());
            }
            case "rotate" -> {
                int grace = p.path("graceHours").asInt(24);
                Map<String, Object> rotated = openApiKeyService.rotate(keyId, grace, actor);
                String plaintext = rotated.get("plaintext") != null ? String.valueOf(rotated.get("plaintext")) : null;
                yield new AssistantWriteTools.ExecutionResult("API_KEY", keyId,
                        "已轮换开放 API 凭证「" + keyName + "」（旧密钥宽限期 " + grace + " 小时）",
                        "查看开放凭证", "/dashboard?tab=security", plaintext, "已轮换生成新凭证");
            }
            case "revoke" -> {
                openApiKeyService.delete(keyId, actor);
                yield new AssistantWriteTools.ExecutionResult("API_KEY", keyId,
                        "已永久吊销开放 API 凭证「" + keyName + "」", "查看开放凭证", "/dashboard?tab=security",
                        null, "凭证已永久吊销");
            }
            default -> throw new IllegalStateException("不支持的凭证操作：" + op);
        };
    }

    // ==================== 辅助解析与指纹 ====================

    private Agent requireManageableAgent(Agent agent, CurrentActor actor) {
        if (!authorizationService.canManageAgent(actor, agent)) {
            throw new IllegalArgumentException(Boolean.TRUE.equals(agent.getIsSystem())
                    ? "「" + agent.getName() + "」是系统公共智能体，仅超级管理员可修改或删除"
                    : "你没有管理智能体「" + agent.getName() + "」的权限");
        }
        return agent;
    }

    // ==================== 分享链接 ====================

    Map<String, Object> draftDisableShareLink(JsonNode args, ToolContext ctx) {
        if (shareLinkService == null) {
            return Map.of("ok", false, "error", "分享链接模块未启用");
        }
        CurrentActor actor = ctx.actor();
        AssistantQueryTools.AgentMatch match = queryTools.resolveAgent(text(args, "agent"), actor);
        if (match.agent() == null) {
            return match.response();
        }
        Agent agent = requireManageableAgent(match.agent(), actor);
        String ref = requiredText(args, "link", 100, "分享链接");
        List<Map<String, Object>> links = shareLinkService.list(agent.getId(), actor);
        List<Map<String, Object>> hits = links.stream()
                .filter(l -> ref.equals(l.get("id")) || ref.equalsIgnoreCase(String.valueOf(l.get("name"))))
                .toList();
        if (hits.isEmpty()) {
            hits = links.stream().filter(l -> String.valueOf(l.get("name")).contains(ref)).toList();
        }
        if (hits.isEmpty()) {
            return Map.of("ok", false, "error", "智能体「" + agent.getName() + "」没有名为「" + ref + "」的分享链接",
                    "available", links.stream().map(l -> l.get("name")).toList());
        }
        if (hits.size() > 1) {
            return Map.of("ok", false, "ambiguous", true, "error", "匹配到多个分享链接，请让用户确认是哪一个",
                    "candidates", hits.stream().map(l -> Map.of("id", l.get("id"), "name", l.get("name"))).toList());
        }
        Map<String, Object> link = hits.get(0);
        if (!"ACTIVE".equals(link.get("status"))) {
            return Map.of("ok", false, "error", "分享链接「" + link.get("name") + "」已经是停用状态，不需要再停用");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agent.getId());
        payload.put("linkId", link.get("id"));
        payload.put("linkName", link.get("name"));
        var preview = previewBuilder()
                .addField("智能体", agent.getName())
                .addField("分享链接", String.valueOf(link.get("name")))
                .addField("当前访客数", String.valueOf(link.get("visitorCount")))
                .setDiff("状态", "启用", "停用")
                .addImpact("停用后，正在对话的访客发下一条消息时会被拒绝，页面显示「链接已失效」")
                .addImpact("链接地址和访问码不变，之后可在调试页「访问入口」重新启用")
                .build();
        return support.createPendingCard(ctx, DISABLE_SHARE_LINK, "B",
                "停用分享链接「" + link.get("name") + "」", payload, preview, "AGENT");
    }

    private AssistantWriteTools.ExecutionResult executeDisableShareLink(JsonNode p, CurrentActor actor) {
        requireBean(shareLinkService, "分享链接模块");
        Agent agent = loadManageableAgent(p.path("agentId").asText(), actor);
        shareLinkService.setEnabled(agent.getId(), p.path("linkId").asText(), false, actor);
        String name = p.path("linkName").asText("");
        return new AssistantWriteTools.ExecutionResult("AGENT", agent.getId(),
                "已停用「" + agent.getName() + "」的分享链接「" + name + "」", "查看分享链接",
                AssistantQueryTools.agentLink(agent.getId()) + "?tab=access", null, "分享链接「" + name + "」已停用");
    }

    private Agent loadManageableAgent(String agentId, CurrentActor actor) {
        Agent agent = agentService.getById(agentId, actor)
                .orElseThrow(() -> new IllegalStateException("智能体已不存在"));
        if (!authorizationService.canManageAgent(actor, agent)) {
            throw new IllegalStateException("你已没有管理该智能体的权限");
        }
        return agent;
    }

    private LlmProviderView resolveProvider(String ref) {
        GatewayOverview ov = gatewayService.overview();
        List<LlmProviderView> list = ov != null && ov.getProviders() != null ? ov.getProviders() : List.of();
        return list.stream()
                .filter(p -> ref.equalsIgnoreCase(p.getId()) || ref.equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到模型通道「" + ref + "」"));
    }

    private record ModelChannelMatch(LlmProviderView provider, String model) {
    }

    private List<LlmProviderView> selectableProviders() {
        if (gatewayService == null) {
            return List.of();
        }
        GatewayOverview ov = gatewayService.overview();
        List<LlmProviderView> all = ov != null && ov.getProviders() != null ? ov.getProviders() : List.of();
        List<LlmProviderView> ready = all.stream()
                .filter(p -> p.isEnabled() && "SUCCESS".equalsIgnoreCase(p.getLastProbeStatus()))
                .toList();
        if (!ready.isEmpty()) {
            return ready;
        }
        return all.stream().filter(LlmProviderView::isEnabled).toList();
    }

    private String resolveProviderNameOrDefault(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "跟随平台默认";
        }
        if (gatewayService == null) {
            return providerId;
        }
        try {
            GatewayOverview ov = gatewayService.overview();
            if (ov != null && ov.getProviders() != null) {
                return ov.getProviders().stream()
                        .filter(p -> providerId.equals(p.getId()))
                        .map(LlmProviderView::getName)
                        .findFirst()
                        .orElse(providerId);
            }
        } catch (RuntimeException ignored) {
        }
        return providerId;
    }

    private static List<String> providerModels(LlmProviderView p) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (p.getDefaultModel() != null && !p.getDefaultModel().isBlank()) {
            set.add(p.getDefaultModel().trim());
        }
        if (p.getModels() != null && !p.getModels().isBlank()) {
            for (String m : p.getModels().split("[,，\\n]")) {
                if (!m.isBlank()) {
                    set.add(m.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private String formatSelectableChannelsHint(List<LlmProviderView> providers) {
        if (providers.isEmpty()) {
            return "（当前没有已启用且连通测试通过的通道）";
        }
        return String.join("、", providers.stream()
                .map(p -> p.getName() + " [" + String.join(", ", providerModels(p)) + "]")
                .toList());
    }

    private LlmProviderView resolveProviderForAgent(String ref) {
        List<LlmProviderView> pool = selectableProviders();
        String key = ref.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        // 1. 精确匹配 ID 或名称
        for (LlmProviderView p : pool) {
            if (key.equalsIgnoreCase(p.getId()) || key.equalsIgnoreCase(p.getName())) {
                return p;
            }
        }
        // 2. 模糊匹配通道名或模型名（如用户传 "qwen3.5-9b" / "9b"）
        List<LlmProviderView> fuzzy = pool.stream()
                .filter(p -> (p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(lower))
                        || providerModels(p).stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains(lower)))
                .toList();
        if (fuzzy.size() == 1) {
            return fuzzy.get(0);
        }
        if (fuzzy.size() > 1) {
            throw new IllegalArgumentException("匹配到多个可选通道，请明确指定：" + formatSelectableChannelsHint(fuzzy));
        }
        throw new IllegalArgumentException("未找到可用的模型通道「" + key + "」；当前可选通道与模型："
                + formatSelectableChannelsHint(pool));
    }

    private String matchModelOnProvider(LlmProviderView provider, String requestedModel) {
        List<String> models = providerModels(provider);
        if (models.isEmpty()) {
            return requestedModel.trim();
        }
        String key = requestedModel.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        for (String m : models) {
            if (m.equalsIgnoreCase(key)) {
                return m;
            }
        }
        List<String> fuzzy = models.stream().filter(m -> m.toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (fuzzy.size() == 1) {
            return fuzzy.get(0);
        }
        throw new IllegalArgumentException("模型「" + key + "」不在通道「" + provider.getName()
                + "」的可用模型列表中（可用：" + String.join("、", models) + "）");
    }

    private ModelChannelMatch resolveModelAcrossSelectableChannels(String requestedModel, String currentPrimaryId) {
        List<LlmProviderView> pool = selectableProviders();
        if (pool.isEmpty()) {
            return null;
        }
        String key = requestedModel.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        // 1. 精确匹配任意可选通道的模型名（若当前绑定通道已命中则优先）
        List<ModelChannelMatch> exact = new ArrayList<>();
        for (LlmProviderView p : pool) {
            for (String m : providerModels(p)) {
                if (m.equalsIgnoreCase(key)) {
                    exact.add(new ModelChannelMatch(p, m));
                }
            }
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            if (currentPrimaryId != null) {
                for (ModelChannelMatch hit : exact) {
                    if (currentPrimaryId.equals(hit.provider().getId())) {
                        return hit;
                    }
                }
            }
            throw new IllegalArgumentException("模型「" + key + "」存在于多个通道，请指定 primaryChannel："
                    + formatSelectableChannelsHint(exact.stream().map(ModelChannelMatch::provider).distinct().toList()));
        }
        // 2. 模糊子串匹配（例如 "9b" 匹配 "qwen3.5:9b" / "ollama(qwen3.5-9b)"）
        List<ModelChannelMatch> fuzzy = new ArrayList<>();
        for (LlmProviderView p : pool) {
            List<String> models = providerModels(p);
            List<String> hitModels = models.stream().filter(m -> m.toLowerCase(Locale.ROOT).contains(lower)).toList();
            if (!hitModels.isEmpty()) {
                for (String m : hitModels) {
                    fuzzy.add(new ModelChannelMatch(p, m));
                }
            } else if (p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(lower) && !models.isEmpty()) {
                fuzzy.add(new ModelChannelMatch(p, models.get(0)));
            }
        }
        if (fuzzy.size() == 1) {
            return fuzzy.get(0);
        }
        if (fuzzy.size() > 1) {
            throw new IllegalArgumentException("模型关键词「" + key + "」匹配到多个可选模型，请明确指定 primaryChannel 和 modelName："
                    + formatSelectableChannelsHint(fuzzy.stream().map(ModelChannelMatch::provider).distinct().toList()));
        }
        throw new IllegalArgumentException("模型「" + key + "」不在任何可用通道中，无法切换。当前可选通道与模型："
                + formatSelectableChannelsHint(pool) + "。请传入准确的 primaryChannel 和 modelName。");
    }

    private List<String> describeProviderImpacts(LlmProviderView p) {
        List<String> impacts = new ArrayList<>();
        GatewayOverview ov = gatewayService.overview();
        if (ov != null && ov.getPolicy() != null) {
            if (Objects.equals(p.getId(), ov.getPolicy().getDefaultProviderId())) {
                impacts.add("该通道当前是全站「默认通道」，变更将影响所有跟随默认路由的智能体");
            }
            if (Objects.equals(p.getId(), ov.getPolicy().getFallbackProviderId())) {
                impacts.add("该通道当前是全站「降级备用通道」");
            }
            if (Objects.equals(p.getId(), ov.getPolicy().getAssistantProviderId())) {
                impacts.add("该通道当前是「平台 AI 助手」专用通道");
            }
        }
        if (impacts.isEmpty()) {
            impacts.add("自选绑定了该通道的智能体对话将受此变更影响");
        }
        return impacts;
    }

    private McpServerView resolveMcpServer(String ref) {
        return mcpServerService.list().stream()
                .filter(s -> ref.equalsIgnoreCase(s.getId()) || ref.equalsIgnoreCase(s.getName()) || ref.equalsIgnoreCase(s.getSlug()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 MCP 服务「" + ref + "」"));
    }

    private UserSummaryDto resolveUser(String ref) {
        return userAdminService.listUsers(null, null, null).stream()
                .filter(u -> ref.equalsIgnoreCase(u.getId()) || ref.equalsIgnoreCase(u.getUsername()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到用户「" + ref + "」"));
    }

    private SkillView resolveSkill(String ref, List<SkillView> visible) {
        return visible.stream()
                .filter(s -> ref.equalsIgnoreCase(s.id()) || ref.equalsIgnoreCase(s.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到可见技能「" + ref + "」"));
    }

    private KnowledgeFaq resolveFaq(String kbId, String ref, CurrentActor actor) {
        PageResult<KnowledgeFaq> page = knowledgeBaseService.searchFaqs(kbId, null, null, 1, 200, actor);
        List<KnowledgeFaq> list = page != null && page.getRecords() != null ? page.getRecords() : List.of();
        return list.stream()
                .filter(f -> ref.equalsIgnoreCase(f.getId()) || ref.equalsIgnoreCase(f.getQuestion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 FAQ「" + ref + "」"));
    }

    private List<String> findAgentsBindingKb(String kbId, CurrentActor actor) {
        PageResult<Agent> all = agentService.searchAgents(null, null, null, null, actor, 1, 500);
        if (all == null || all.getRecords() == null) {
            return List.of();
        }
        return all.getRecords().stream()
                .filter(a -> a.getKnowledgeBaseIds() != null && a.getKnowledgeBaseIds().contains(kbId))
                .map(Agent::getName)
                .toList();
    }

    private String computeUpdatedToolsConfig(String existingJson, List<String> addTools, List<String> removeTools) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(existingJson);
                if (root.isArray()) {
                    for (JsonNode n : root) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> item = objectMapper.convertValue(n, LinkedHashMap.class);
                        list.add(item);
                    }
                }
            } catch (Exception ignored) {
                // 现有 JSON 损坏时从空开始重建
            }
        }
        List<PlatformToolView> catalog = platformToolService != null ? platformToolService.list() : List.of();
        for (String ref : addTools) {
            PlatformToolView found = catalog.stream()
                    .filter(t -> ref.equalsIgnoreCase(t.getCode()) || ref.equalsIgnoreCase(t.getName()) || ref.equalsIgnoreCase(t.getTitle()))
                    .findFirst().orElse(null);
            String code = found != null ? found.getCode() : ref;
            String title = found != null && found.getTitle() != null ? found.getTitle() : code;
            boolean exists = false;
            for (Map<String, Object> item : list) {
                if (code.equalsIgnoreCase(String.valueOf(item.get("name"))) || code.equalsIgnoreCase(String.valueOf(item.get("code")))) {
                    item.put("enabled", true);
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Map<String, Object> added = new LinkedHashMap<>();
                added.put("name", code);
                added.put("title", title);
                added.put("enabled", true);
                list.add(added);
            }
        }
        for (String ref : removeTools) {
            for (Map<String, Object> item : list) {
                if (ref.equalsIgnoreCase(String.valueOf(item.get("name")))
                        || ref.equalsIgnoreCase(String.valueOf(item.get("code")))
                        || ref.equalsIgnoreCase(String.valueOf(item.get("title")))) {
                    item.put("enabled", false);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("工具配置序列化失败");
        }
    }

    private static List<String> skillNamesOf(Set<String> ids, List<SkillView> visible) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (SkillView s : visible) byId.put(s.id(), s.name());
        return ids.stream().map(id -> byId.getOrDefault(id, id)).toList();
    }

    private static String agentSettingsFingerprint(Agent a) {
        return sha256(String.join("|",
                String.valueOf(a.getPrimaryProviderId()),
                String.valueOf(a.getFallbackProviderId()),
                String.valueOf(a.getModelName()),
                String.valueOf(a.getTemperature()),
                String.valueOf(a.getTopP()),
                String.valueOf(a.getMaxTokens()),
                String.valueOf(a.resolveMemoryTurns()),
                Agent.normalizeThinkingMode(a.getThinkingMode()),
                Agent.normalizeKnowledgeMode(a.getKnowledgeMode()),
                String.valueOf(a.getSkillIds()),
                String.valueOf(a.getToolsConfig())));
    }

    private static String kbSettingsFingerprint(KnowledgeBase kb) {
        return sha256(String.join("|",
                String.valueOf(kb.getSearchMethod()),
                String.valueOf(kb.getTopK()),
                String.valueOf(kb.getScoreThreshold()),
                String.valueOf(kb.getVectorWeight()),
                String.valueOf(kb.getKeywordWeight()),
                String.valueOf(kb.getRerankEnabled()),
                String.valueOf(kb.getRerankMode())));
    }

    private static String extractUserId(Map<String, Object> created) {
        if (created == null) return null;
        Object user = created.get("user");
        if (user instanceof UserSummaryDto dto) return dto.getId();
        if (user instanceof Map<?, ?> m && m.get("id") != null) return String.valueOf(m.get("id"));
        return created.get("userId") != null ? String.valueOf(created.get("userId")) : null;
    }

    private static String deleteTypeLabel(String type) {
        return switch (type) {
            case "agent" -> "智能体";
            case "knowledge_base" -> "知识库";
            case "document" -> "文档";
            case "faq" -> "FAQ";
            case "http_tool" -> "自定义 HTTP 工具";
            case "skill" -> "技能";
            case "template" -> "场景模板";
            case "mcp_server" -> "MCP 服务";
            default -> type;
        };
    }

    private static void requireSuperAdmin(CurrentActor actor, String action) {
        if (actor == null || !actor.isSuperAdmin()) {
            throw new IllegalStateException(action + "仅超级管理员可用");
        }
    }

    private static void requireBean(Object bean, String name) {
        if (bean == null) {
            throw new IllegalStateException(name + "当前未启用");
        }
    }

    private static String blankOr(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String joinOrEmpty(List<String> list) {
        return list == null || list.isEmpty() ? "（无）" : String.join("、", list);
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
