package com.example.agentplatform.assistant;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.AssistantAction;
import com.example.agentplatform.model.AssistantEvalRun;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.repository.AssistantConversationRepository;
import com.example.agentplatform.repository.AssistantEvalRunRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.AgentService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        AssistantEvalRun saved = runRepository.save(run);
        executor.execute(() -> execute(saved.getId(), selected));
        return saved;
    }

    public List<AssistantEvalRun> recentRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc();
    }

    public java.util.Optional<AssistantEvalRun> findRun(String id) {
        return runRepository.findById(id);
    }

    void execute(String runId, List<AssistantEvalCase> selected) {
        AssistantEvalRun run = runRepository.findById(runId).orElseThrow();
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Map<String, String> placeholders = resolvePlaceholders();
            for (AssistantEvalCase evalCase : selected) {
                Map<String, Object> result = runCase(evalCase, placeholders);
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
            log.info("Assistant eval run [{}] {}: {}/{} passed", runId, run.getStatus(), run.getPassed(), run.getTotal());
        }
    }

    Map<String, Object> runCase(AssistantEvalCase evalCase, Map<String, String> placeholders) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", evalCase.id());
        r.put("category", evalCase.category());
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
            List<String> failures = AssistantEvalScorer.score(expect,
                    new AssistantEvalScorer.Observation(capture.content, ctx.calls(), cardTools));
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
            agents.stream().filter(a -> a.getStatus() == AgentStatus.RUNNING)
                    .sorted((a, b) -> Boolean.compare(Boolean.TRUE.equals(a.getIsSystem()), Boolean.TRUE.equals(b.getIsSystem())))
                    .findFirst().ifPresent(a -> {
                        values.put("agent", a.getName());
                        values.put("agent_id", a.getId());
                    });
            agents.stream().filter(a -> Boolean.TRUE.equals(a.getIsSystem()))
                    .findFirst().ifPresent(a -> values.put("system_agent", a.getName()));
            List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledgeBases(null, null, 1, 50, admin).getRecords();
            kbs.stream().filter(kb -> !PlatformDocsService.KB_NAME.equals(kb.getName())).findFirst()
                    .or(() -> kbs.stream().findFirst())
                    .ifPresent(kb -> {
                        values.put("kb", kb.getName());
                        values.put("kb_id", kb.getId());
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
        if (run.getModel() == null && result.get("model") != null) {
            run.setModel(String.valueOf(result.get("model")));
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
            } else if ("error".equals(event)) {
                error = String.valueOf(map.get("message"));
            }
        }
    }
}
