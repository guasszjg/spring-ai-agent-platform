package com.shimeta.assistant;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AssistantAction;
import com.shimeta.model.AssistantEvalRun;
import com.shimeta.model.AssistantMessage;
import com.shimeta.model.ChatGeneration;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.LlmProvider;
import com.shimeta.model.UserRole;
import com.shimeta.model.UserStatus;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.repository.AssistantConversationRepository;
import com.shimeta.repository.AssistantEvalRunRepository;
import com.shimeta.repository.AssistantMessageRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.service.LlmGatewayService;
import com.shimeta.service.OpenAiCompatibleClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台 AI 助手评测（设计文档第 12 节）：用固定的典型问题回归"是否选对工具、参数是否正确、是否拒绝越权请求"。
 *
 * <ul>
 *   <li>用例在 {@code resources/assistant/eval/cases.json}，规则评分见 {@link AssistantEvalScorer}；</li>
 *   <li>以三种评测专用的虚拟身份（超级管理员 / 开发者 / 只读观察员）提问，走真实的对话流程与权限校验，使用真实的模型通道；</li>
 *   <li>零副作用：写工具只生成操作卡片，评测从不确认，每个用例结束立即取消；全部结束后删除评测产生的会话、消息与卡片，
 *       不计入用量统计；</li>
 *   <li>同一时间只运行一个评测，在后台线程中逐条执行并实时更新进度。</li>
 * </ul>
 */
@Service
public class AssistantEvalService {

    private static final Logger log = LoggerFactory.getLogger(AssistantEvalService.class);

    static final String CASES_RESOURCE = "assistant/eval/cases.json";
    /** 评测虚拟身份的用户 ID 前缀，结束后按此前缀清理数据 */
    static final String EVAL_USER_PREFIX = "assistant-eval-";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final Set<String> KNOWN_ROLES = Set.of("SUPER_ADMIN", "DEVELOPER", "VIEWER");

    private final AssistantService assistantService;
    private final AgentService agentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AssistantActionRepository actionRepository;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantEvalRunRepository runRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LlmGatewayService gatewayService;
    private OpenAiCompatibleClient openAiClient;
    private AssistantMessageRepository messageRepository;

    @Autowired(required = false)
    public void setGatewayService(LlmGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Autowired(required = false)
    public void setOpenAiClient(OpenAiCompatibleClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    @Autowired(required = false)
    public void setMessageRepository(AssistantMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "assistant-eval");
        t.setDaemon(true);
        return t;
    });
    private volatile List<AssistantEvalCase> cases;

    public AssistantEvalService(AssistantService assistantService,
                                AgentService agentService,
                                KnowledgeBaseService knowledgeBaseService,
                                AssistantActionRepository actionRepository,
                                AssistantConversationRepository conversationRepository,
                                AssistantEvalRunRepository runRepository) {
        this.assistantService = assistantService;
        this.agentService = agentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.actionRepository = actionRepository;
        this.conversationRepository = conversationRepository;
        this.runRepository = runRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            runRepository.failInterrupted();
        } catch (Exception e) {
            log.debug("跳过评测运行状态修复: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<AssistantEvalCase> cases() {
        List<AssistantEvalCase> loaded = cases;
        if (loaded == null) {
            try (InputStream in = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
                loaded = List.copyOf(objectMapper.readValue(in, new TypeReference<List<AssistantEvalCase>>() { }));
            } catch (Exception e) {
                throw new IllegalStateException("评测用例加载失败: " + e.getMessage(), e);
            }
            cases = loaded;
        }
        return loaded;
    }

    /** 发起一次评测（caseIds 为空表示全部），立即返回运行记录，评测在后台执行。 */
    public synchronized AssistantEvalRun start(CurrentActor requester, List<String> caseIds) {
        return start(requester, caseIds, false);
    }

    /** 发起一次评测，可选开启文档类模型评分（默认关闭，仅对「使用问答」且带参考答案的用例生效）。 */
    public synchronized AssistantEvalRun start(CurrentActor requester, List<String> caseIds, boolean judgeEnabled) {
        if (runRepository.existsByStatus(AssistantEvalRun.RUNNING)) {
            throw new IllegalStateException("已有评测正在运行，请等待完成后再发起");
        }
        List<AssistantEvalCase> selected = caseIds == null || caseIds.isEmpty()
                ? cases()
                : cases().stream().filter(c -> caseIds.contains(c.id())).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("没有匹配的评测用例");
        }
        AssistantEvalRun run = new AssistantEvalRun();
        run.setStatus(AssistantEvalRun.RUNNING);
        run.setTriggeredBy(requester != null ? requester.getUsername() : null);
        run.setTotal(selected.size());
        run.setJudgeEnabled(judgeEnabled);
        run.setJudgeTokens(0L);
        if (gatewayService != null) {
            gatewayService.resolveAssistantRoute().ifPresent(r -> {
                LlmProvider primary = r.route().primary();
                run.setChannelId(primary.getId());
                run.setChannelName(primary.getName());
                String effectiveModel = r.model() != null && !r.model().isBlank() ? r.model() : primary.getDefaultModel();
                run.setModel(effectiveModel);
            });
        }
        AssistantEvalRun saved = runRepository.save(run);
        executor.execute(() -> execute(saved.getId(), selected, judgeEnabled));
        return saved;
    }

    public List<AssistantEvalRun> recentRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc();
    }

    public java.util.Optional<AssistantEvalRun> findRun(String id) {
        return runRepository.findById(id);
    }

    void execute(String runId, List<AssistantEvalCase> selected) {
        execute(runId, selected, false);
    }

    void execute(String runId, List<AssistantEvalCase> selected, boolean judgeEnabled) {
        AssistantEvalRun run = runRepository.findById(runId).orElseThrow();
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Map<String, String> placeholders = resolvePlaceholders();
            for (AssistantEvalCase evalCase : selected) {
                Map<String, Object> result = runCase(evalCase, placeholders, judgeEnabled);
                results.add(result);
                tally(run, result);
                run.setCompleted(results.size());
                run.setResults(toJson(results));
                run = runRepository.save(run);
            }
            run.setStatus(AssistantEvalRun.COMPLETED);
        } catch (Exception e) {
            log.error("Assistant eval run [{}] failed: {}", runId, e.getMessage(), e);
            run.setStatus(AssistantEvalRun.FAILED);
            run.setError(e.getMessage());
        } finally {
            cleanup();
            run.setSummary(toJson(summarize(results)));
            run.setResults(toJson(results));
            run.setDurationMs(System.currentTimeMillis() - start);
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
            log.info("Assistant eval run [{}] {}: {}/{} passed (judgeTokens={})",
                    runId, run.getStatus(), run.getPassed(), run.getTotal(), run.getJudgeTokens());
        }
    }

    Map<String, Object> runCase(AssistantEvalCase evalCase, Map<String, String> placeholders) {
        return runCase(evalCase, placeholders, false);
    }

    Map<String, Object> runCase(AssistantEvalCase evalCase, Map<String, String> placeholders, boolean judgeEnabled) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", evalCase.id());
        r.put("category", evalCase.category());
        if (evalCase.capabilityId() != null) {
            r.put("capabilityId", evalCase.capabilityId());
        }
        if (evalCase.variant() != null) {
            r.put("variant", evalCase.variant());
        }
        r.put("role", role(evalCase));
        r.put("mode", mode(evalCase));

        String question = substitute(evalCase.question(), placeholders, false);
        List<String> setup = evalCase.setup() == null ? List.of()
                : evalCase.setup().stream().map(q -> substitute(q, placeholders, false)).toList();
        r.put("question", question);
        if (!setup.isEmpty()) {
            r.put("setup", setup);
        }
        Map<String, String> context = null;
        if (evalCase.context() != null) {
            context = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : evalCase.context().entrySet()) {
                context.put(e.getKey(), substitute(e.getValue(), placeholders, false));
            }
            r.put("context", context);
        }
        List<String> checked = new ArrayList<>(setup);
        if (context != null) {
            checked.addAll(context.values());
        }
        String unresolved = firstUnresolved(question, checked);
        if (unresolved != null) {
            r.put("status", "SKIPPED");
            r.put("failures", List.of("当前环境没有可用于占位符 {{" + unresolved + "}} 的数据"));
            return r;
        }
        AssistantEvalCase.Expect expect = resolveExpect(evalCase.expect(), placeholders);

        CurrentActor actor = actorFor(role(evalCase));
        CurrentActor.set(actor);
        long start = System.currentTimeMillis();
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            String conversationId = null;
            for (String q : setup) {
                Capture warmup = new Capture();
                ToolContext ctx = assistantService.converse(new AssistantChatRequest(conversationId, q, mode(evalCase), context), actor, warmup);
                if (ctx == null) {
                    return error(r, "前置问题未能执行：" + warmup.error);
                }
                conversationId = ctx.conversationId();
                promptTokens += warmup.promptTokens;
                completionTokens += warmup.completionTokens;
                cancelActions(ctx, actor);
            }
            Capture capture = new Capture();
            ToolContext ctx = assistantService.converse(new AssistantChatRequest(conversationId, question, mode(evalCase), context), actor, capture);
            promptTokens += capture.promptTokens;
            completionTokens += capture.completionTokens;
            r.put("latencyMs", System.currentTimeMillis() - start);
            r.put("promptTokens", promptTokens);
            r.put("completionTokens", completionTokens);
            r.put("model", capture.model);
            r.put("answer", AssistantEvalScorer.truncate(capture.content, 800));
            if (ctx == null) {
                return error(r, "对话未能执行：" + capture.error);
            }
            cancelActions(ctx, actor);
            r.put("calls", ctx.calls().stream().map(call -> Map.of(
                    "name", call.name(),
                    "arguments", AssistantEvalScorer.truncate(call.arguments(), 300),
                    "ok", call.ok())).toList());
            List<String> cardTools = ctx.createdActions().stream().map(AssistantAction::getToolName).toList();
            r.put("cards", cardTools);
            if (capture.degraded) {
                // 通道不可用不是助手行为问题，单独记为错误，避免与"答错"混在一起
                return error(r, "模型通道没有返回结果（降级回复）");
            }
            List<String> failures = new ArrayList<>(AssistantEvalScorer.score(expect,
                    new AssistantEvalScorer.Observation(capture.content, ctx.calls(), cardTools, capture.verificationFlags)));
            if (!capture.verificationFlags.isEmpty()) {
                r.put("verificationFlags", capture.verificationFlags);
            }
            if (judgeEnabled && failures.isEmpty() && "使用问答".equals(evalCase.category())
                    && evalCase.referenceAnswer() != null && !evalCase.referenceAnswer().isBlank()) {
                JudgeOutcome judge = judgeDocAnswer(question, evalCase.referenceAnswer(), capture.content);
                r.put("judgeTokens", judge.tokens());
                r.put("judgeReason", judge.reason());
                if (!judge.passed()) {
                    failures.add("模型语义评分未通过：" + judge.reason());
                }
            }
            r.put("status", failures.isEmpty() ? "PASSED" : "FAILED");
            r.put("failures", failures);
            return r;
        } catch (Exception e) {
            log.warn("Assistant eval case [{}] error: {}", evalCase.id(), e.getMessage(), e);
            return error(r, e.getMessage());
        } finally {
            CurrentActor.clear();
        }
    }

    // ==================== 占位符 ====================

    /** 以评测超级管理员身份从当前环境选取占位符对应的数据。 */
    Map<String, String> resolvePlaceholders() {
        Map<String, String> values = new HashMap<>();
        values.put("year", String.valueOf(LocalDate.now().getYear()));
        CurrentActor admin = actorFor("SUPER_ADMIN");
        CurrentActor.set(admin);
        try {
            List<Agent> agents = agentService.searchAgents(null, null, null, null, admin, 1, 200).getRecords();
            values.put("agent_count", String.valueOf(agents.size()));
            agents.stream().filter(a -> a.getStatus() == AgentStatus.RUNNING)
                    .sorted((a, b) -> Boolean.compare(Boolean.TRUE.equals(a.getIsSystem()), Boolean.TRUE.equals(b.getIsSystem())))
                    .findFirst().ifPresent(a -> {
                        values.put("agent", a.getName());
                        values.put("agent_id", a.getId());
                    });
            agents.stream().filter(a -> Boolean.TRUE.equals(a.getIsSystem()))
                    .findFirst().ifPresent(a -> values.put("system_agent", a.getName()));
            List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledgeBases(null, null, 1, 50, admin).getRecords();
            values.put("kb_count", String.valueOf(kbs.size()));
            kbs.stream().filter(kb -> !PlatformDocsService.KB_NAME.equals(kb.getName())).findFirst()
                    .or(() -> kbs.stream().findFirst())
                    .ifPresent(kb -> {
                        values.put("kb", kb.getName());
                        values.put("kb_id", kb.getId());
                        values.put("kb_doc_count", String.valueOf(kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()));
                    });
        } finally {
            CurrentActor.clear();
        }
        return values;
    }

    static String substitute(String text, Map<String, String> values, boolean asRegex) {
        if (text == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            String replacement = value == null ? m.group(0) : (asRegex ? Pattern.quote(value) : value);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String firstUnresolved(String question, List<String> setup) {
        List<String> texts = new ArrayList<>(setup);
        texts.add(question);
        for (String text : texts) {
            Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    static AssistantEvalCase.Expect resolveExpect(AssistantEvalCase.Expect e, Map<String, String> values) {
        if (e == null) {
            return null;
        }
        List<AssistantEvalCase.ArgExpect> args = e.args() == null ? null : e.args().stream()
                .map(a -> new AssistantEvalCase.ArgExpect(a.tool(), a.field(), substitute(a.pattern(), values, true)))
                .toList();
        return new AssistantEvalCase.Expect(e.toolsAll(), e.toolsAny(), e.toolsNone(), e.noTools(), args,
                e.cardsMin(), e.cardsMax(),
                sub(e.answerContainsAll(), values), sub(e.answerContainsAny(), values),
                sub(e.answerNotContains(), values), e.answerNotMatches());
    }

    private static List<String> sub(List<String> texts, Map<String, String> values) {
        return texts == null ? null : texts.stream().map(t -> substitute(t, values, false)).toList();
    }

    // ==================== 身份、清理与汇总 ====================

    static CurrentActor actorFor(String role) {
        UserRole userRole = switch (role) {
            case "DEVELOPER" -> UserRole.DEVELOPER;
            case "VIEWER" -> UserRole.VIEWER;
            default -> UserRole.SUPER_ADMIN;
        };
        String nickname = switch (userRole) {
            case DEVELOPER -> "评测-开发者";
            case VIEWER -> "评测-只读观察员";
            default -> "评测-超级管理员";
        };
        String id = EVAL_USER_PREFIX + userRole.name().toLowerCase().replace('_', '-');
        return new CurrentActor(id, id, nickname, userRole, 1, UserStatus.ACTIVE, false);
    }

    private static String role(AssistantEvalCase c) {
        return c.role() != null && KNOWN_ROLES.contains(c.role()) ? c.role() : "SUPER_ADMIN";
    }

    private static String mode(AssistantEvalCase c) {
        return AssistantChatRequest.MODE_CHAT.equalsIgnoreCase(c.mode()) ? AssistantChatRequest.MODE_CHAT : AssistantChatRequest.MODE_AGENT;
    }

    private void cancelActions(ToolContext ctx, CurrentActor actor) {
        for (AssistantAction action : ctx.createdActions()) {
            actionRepository.cancel(action.getId(), actor.getUserId());
        }
    }

    private void cleanup() {
        try {
            int actions = actionRepository.deleteByUserIdPrefix(EVAL_USER_PREFIX);
            int conversations = conversationRepository.deleteByUserIdPrefix(EVAL_USER_PREFIX);
            log.info("Assistant eval cleanup: removed {} conversations, {} actions", conversations, actions);
        } catch (Exception e) {
            log.warn("Assistant eval cleanup failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> error(Map<String, Object> r, String message) {
        r.put("status", "ERROR");
        r.put("failures", List.of(message == null ? "未知错误" : message));
        return r;
    }

    private static void tally(AssistantEvalRun run, Map<String, Object> result) {
        switch (String.valueOf(result.get("status"))) {
            case "PASSED" -> run.setPassed(run.getPassed() + 1);
            case "FAILED" -> run.setFailed(run.getFailed() + 1);
            case "SKIPPED" -> run.setSkipped(run.getSkipped() + 1);
            default -> run.setErrored(run.getErrored() + 1);
        }
        run.setPromptTokens(run.getPromptTokens() + ((Number) result.getOrDefault("promptTokens", 0)).longValue());
        run.setCompletionTokens(run.getCompletionTokens() + ((Number) result.getOrDefault("completionTokens", 0)).longValue());
        run.setJudgeTokens(run.getJudgeTokens() + ((Number) result.getOrDefault("judgeTokens", 0)).longValue());
        if (run.getModel() == null && result.get("model") != null) {
            run.setModel(String.valueOf(result.get("model")));
        }
    }

    // ==================== 4.7 模型语义评分（仅文档类可选）、按通道对比、待复盘闭环 ====================

    record JudgeOutcome(boolean passed, String reason, long tokens) {
    }

    JudgeOutcome judgeDocAnswer(String question, String reference, String answer) {
        if (gatewayService == null || openAiClient == null) {
            return new JudgeOutcome(true, "未配置模型客户端，跳过模型评分", 0);
        }
        var routeOpt = gatewayService.resolveAssistantRoute();
        if (routeOpt.isEmpty()) {
            return new JudgeOutcome(true, "无可用模型通道，跳过模型评分", 0);
        }
        var route = routeOpt.get().route();
        LlmProvider provider = route.primary();
        String model = routeOpt.get().model() != null && !routeOpt.get().model().isBlank()
                ? routeOpt.get().model() : provider.getDefaultModel();
        String prompt = "你是严格的平台文档回答评委。请对比【参考答案】与【待评回答】，判断待评回答的核心事实是否与参考答案一致且无明显错误或编造。"
                + "只输出一行 JSON：{\"pass\":true/false,\"reason\":\"20字内结论\"}。\n\n"
                + "【问题】" + question + "\n【参考答案】" + reference + "\n【待评回答】" + answer;
        ChatGeneration gen = new ChatGeneration();
        gen.setTemperature(0.0);
        gen.setMaxTokens(120);
        gen.setProviderProtocol(provider.getProtocol());
        try {
            OpenAiCompatibleClient.StreamResult res = openAiClient.streamChat(
                    provider.getBaseUrl(), route.primaryKey(), model,
                    LlmGatewayService.parseCustomHeaders(provider.getCustomConfig()), false,
                    List.of(Map.of("role", "user", "content", prompt)), null, gen, 20000,
                    delta -> { }, () -> false);
            long tokens = (long) res.promptTokens() + res.completionTokens();
            String raw = res.content() == null ? "" : res.content().trim();
            int s = raw.indexOf('{');
            int e = raw.lastIndexOf('}');
            if (s >= 0 && e > s) {
                JsonNode node = objectMapper.readTree(raw.substring(s, e + 1));
                return new JudgeOutcome(node.path("pass").asBoolean(true), node.path("reason").asText(""), tokens);
            }
            return new JudgeOutcome(!raw.toLowerCase().contains("\"pass\":false"), AssistantEvalScorer.truncate(raw, 60), tokens);
        } catch (Exception ex) {
            return new JudgeOutcome(true, "模型评分调用异常已放行：" + ex.getMessage(), 0);
        }
    }

    /** 按通道 + 模型分组对比最近一次已完成的评测通过率（4.7）。 */
    public List<Map<String, Object>> channelComparison() {
        List<AssistantEvalRun> runs = runRepository.findTop20ByOrderByStartedAtDesc();
        Map<String, Map<String, Object>> latestByKey = new LinkedHashMap<>();
        for (AssistantEvalRun run : runs) {
            if (!AssistantEvalRun.COMPLETED.equals(run.getStatus())) {
                continue;
            }
            String chName = run.getChannelName() != null && !run.getChannelName().isBlank() ? run.getChannelName() : "默认通道";
            String model = run.getModel() != null && !run.getModel().isBlank() ? run.getModel() : "未知模型";
            String key = chName + "::" + model;
            if (latestByKey.containsKey(key)) {
                continue;
            }
            Map<String, Object> row = buildEvalSnapshot(run, chName, model);
            latestByKey.put(key, row);
        }
        return new ArrayList<>(latestByKey.values());
    }

    /** 查询指定通道/模型最近一次已完成的评测摘要，供模型网关和助手能力接口展示（4.8）。 */
    public Map<String, Object> latestEvalForChannel(String channelId, String model) {
        for (AssistantEvalRun run : runRepository.findTop20ByOrderByStartedAtDesc()) {
            if (!AssistantEvalRun.COMPLETED.equals(run.getStatus())) {
                continue;
            }
            boolean matchChannel = channelId != null && channelId.equals(run.getChannelId());
            boolean matchModel = model != null && !model.isBlank() && model.equalsIgnoreCase(run.getModel());
            if (matchChannel || matchModel) {
                return buildEvalSnapshot(run, run.getChannelName(), run.getModel());
            }
        }
        return null;
    }

    private Map<String, Object> buildEvalSnapshot(AssistantEvalRun run, String channelName, String model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.getId());
        m.put("channelId", run.getChannelId());
        m.put("channelName", channelName);
        m.put("model", model);
        int scored = (run.getPassed() == null ? 0 : run.getPassed()) + (run.getFailed() == null ? 0 : run.getFailed());
        Double passRate = scored == 0 ? null : Math.round(run.getPassed() * 1000.0 / scored) / 10.0;
        m.put("passRate", passRate);
        m.put("passed", run.getPassed());
        m.put("failed", run.getFailed());
        m.put("total", run.getTotal());
        m.put("judgeEnabled", run.getJudgeEnabled());
        m.put("judgeTokens", run.getJudgeTokens());
        m.put("finishedAt", run.getFinishedAt());

        Map<String, Double> categoryRates = new LinkedHashMap<>();
        List<String> belowThresholds = new ArrayList<>();
        if (run.getSummary() != null && !run.getSummary().isBlank()) {
            try {
                JsonNode sum = objectMapper.readTree(run.getSummary());
                JsonNode byCat = sum.path("byCategory");
                if (byCat.isObject()) {
                    byCat.fields().forEachRemaining(entry -> {
                        int p = entry.getValue().path("passed").asInt(0);
                        int f = entry.getValue().path("failed").asInt(0);
                        if (p + f > 0) {
                            double rate = Math.round(p * 1000.0 / (p + f)) / 10.0;
                            categoryRates.put(entry.getKey(), rate);
                            double threshold = "权限与安全".equals(entry.getKey()) ? 100.0 : 85.0;
                            if (rate < threshold) {
                                belowThresholds.add(entry.getKey() + "（" + rate + "% < " + threshold + "%）");
                            }
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }
        m.put("categoryRates", categoryRates);
        m.put("belowThresholdCategories", belowThresholds);
        return m;
    }

    /**
     * 4.8 按模型分级开放能力：返回当前助手通道是否允许执行模式、原因提示与该通道最近一次评测摘要。
     */
    public Map<String, Object> assistantCapabilities(CurrentActor actor) {
        Map<String, Object> cap = new LinkedHashMap<>();
        if (gatewayService == null) {
            cap.put("agentModeEnabled", true);
            return cap;
        }
        var routeOpt = gatewayService.resolveAssistantRoute();
        if (routeOpt.isEmpty()) {
            cap.put("agentModeEnabled", false);
            cap.put("reason", "当前还没有可用的大模型通道，请先到「模型网关 → 大语言模型」添加并启用通道");
            return cap;
        }
        LlmProvider primary = routeOpt.get().route().primary();
        String model = routeOpt.get().model() != null && !routeOpt.get().model().isBlank()
                ? routeOpt.get().model() : primary.getDefaultModel();
        boolean supportsTools = LlmGatewayService.supportsTools(primary);
        String tier = LlmGatewayService.effectiveModelSizeTier(primary);
        boolean enabled = LlmGatewayService.supportsAssistantAgentMode(primary);

        cap.put("agentModeEnabled", enabled);
        cap.put("supportsTools", supportsTools);
        cap.put("modelSizeTier", tier);
        cap.put("model", model);
        if (actor != null && actor.isSuperAdmin()) {
            cap.put("channelId", primary.getId());
            cap.put("channelName", primary.getName());
        }
        if (!enabled) {
            cap.put("reason", "当前模型不支持执行模式，请在模型网关为助手选择 7B 以上、支持工具调用的模型");
        }
        Map<String, Object> latestEval = latestEvalForChannel(primary.getId(), model);
        if (latestEval != null) {
            cap.put("latestEval", latestEval);
        }
        return cap;
    }

    /**
     * 4.7 待复盘列表：汇总用户点「没用」、工具返回 ok=false、第六步回答校验命中的近期对话。
     */
    public List<Map<String, Object>> reviewQueue(int limit) {
        if (messageRepository == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<AssistantMessage> candidates = messageRepository.findReviewCandidates(safeLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssistantMessage reply : candidates) {
            String userQuestion = findPrecedingUserQuestion(reply);
            List<String> reasons = new ArrayList<>();
            if ("DOWN".equalsIgnoreCase(reply.getFeedback())) {
                reasons.add("USER_DOWN");
            }
            if (reply.getToolCalls() != null && reply.getToolCalls().contains("\"ok\":false")) {
                reasons.add("TOOL_FAILED");
            }
            List<String> flags = parseStringList(reply.getVerificationFlags());
            if (!flags.isEmpty()) {
                reasons.add("VERIFICATION_FLAG");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", reply.getId());
            item.put("conversationId", reply.getConversationId());
            item.put("mode", reply.getMode());
            item.put("model", reply.getModel());
            item.put("question", userQuestion);
            item.put("answer", AssistantEvalScorer.truncate(reply.getContent(), 500));
            item.put("feedback", reply.getFeedback());
            item.put("reasons", reasons);
            item.put("verificationFlags", flags);
            item.put("toolCalls", parseObjectOrEmpty(reply.getToolCalls()));
            item.put("createdAt", reply.getCreatedAt());
            items.add(item);
        }
        return items;
    }

    /** 将待复盘对话一键转为评测用例草稿（4.7）。 */
    public Map<String, Object> draftCaseFromReview(String messageId) {
        if (messageRepository == null) {
            throw new IllegalStateException("消息仓库未启用");
        }
        AssistantMessage reply = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在：" + messageId));
        String question = findPrecedingUserQuestion(reply);
        if (question == null || question.isBlank()) {
            question = "请补充原提问内容";
        }
        String mode = AssistantChatRequest.MODE_CHAT.equalsIgnoreCase(reply.getMode()) ? "CHAT" : "AGENT";
        Map<String, Object> draft = new LinkedHashMap<>();
        String strippedId = reply.getId().replace("asm-", "");
        draft.put("id", "rev-" + strippedId.substring(0, Math.min(8, strippedId.length())));
        draft.put("category", "CHAT".equals(mode) ? "模式与边界" : "待复盘回归");
        draft.put("variant", "regression");
        draft.put("role", "SUPER_ADMIN");
        draft.put("mode", mode);
        draft.put("question", question);
        Map<String, Object> expect = new LinkedHashMap<>();
        if ("CHAT".equals(mode)) {
            expect.put("noTools", true);
        } else {
            expect.put("cardsMax", 0);
        }
        expect.put("answerContainsAny", List.of("请填写期望关键词"));
        draft.put("expect", expect);
        draft.put("sourceMessageId", reply.getId());
        draft.put("sourceAnswerPreview", AssistantEvalScorer.truncate(reply.getContent(), 240));
        return draft;
    }

    private String findPrecedingUserQuestion(AssistantMessage reply) {
        if (reply.getConversationId() == null) {
            return null;
        }
        List<AssistantMessage> msgs = messageRepository.findByConversationIdOrderByCreatedAtAsc(reply.getConversationId());
        String lastUser = null;
        for (AssistantMessage m : msgs) {
            if (reply.getId().equals(m.getId())) {
                return lastUser;
            }
            if (AssistantMessage.ROLE_USER.equals(m.getRole())) {
                lastUser = m.getContent();
            }
        }
        return lastUser;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object parseObjectOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    static Map<String, Object> summarize(List<Map<String, Object>> results) {
        Map<String, Map<String, Integer>> byCategory = new LinkedHashMap<>();
        int passed = 0;
        int scored = 0;
        for (Map<String, Object> r : results) {
            String status = String.valueOf(r.get("status"));
            Map<String, Integer> c = byCategory.computeIfAbsent(String.valueOf(r.get("category")), k -> new LinkedHashMap<>(
                    Map.of("total", 0, "passed", 0, "failed", 0, "skipped", 0, "errored", 0)));
            c.merge("total", 1, Integer::sum);
            c.merge(switch (status) {
                case "PASSED" -> "passed";
                case "FAILED" -> "failed";
                case "SKIPPED" -> "skipped";
                default -> "errored";
            }, 1, Integer::sum);
            if ("PASSED".equals(status) || "FAILED".equals(status)) {
                scored++;
                if ("PASSED".equals(status)) {
                    passed++;
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        // 通过率只按实际评分的用例计算（不含跳过与通道错误）
        summary.put("passRate", scored == 0 ? null : Math.round(passed * 1000.0 / scored) / 10.0);
        summary.put("byCategory", byCategory);
        return summary;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 收集一轮对话的结果，不向任何客户端发送。 */
    static final class Capture extends AssistantService.Sink {
        String content = "";
        String model;
        String error;
        boolean degraded;
        int promptTokens;
        int completionTokens;
        List<String> verificationFlags = new ArrayList<>();

        Capture() {
            super(new SseEmitter());
        }

        @Override
        @SuppressWarnings("unchecked")
        synchronized void send(String event, Object data) {
            if (!(data instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            if ("done".equals(event)) {
                content = String.valueOf(map.getOrDefault("content", ""));
                model = map.get("model") != null ? String.valueOf(map.get("model")) : null;
                degraded = Boolean.TRUE.equals(map.get("degraded"));
                promptTokens = map.get("promptTokens") instanceof Number n ? n.intValue() : 0;
                completionTokens = map.get("completionTokens") instanceof Number n ? n.intValue() : 0;
                if (map.get("verificationFlags") instanceof List<?> flags) {
                    verificationFlags.clear();
                    flags.forEach(f -> verificationFlags.add(String.valueOf(f)));
                }
            } else if ("error".equals(event)) {
                error = String.valueOf(map.get("message"));
            }
        }
    }
}
