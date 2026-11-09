package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.RagEvalCase;
import com.shimeta.model.RagEvalRun;
import com.shimeta.model.RagEvalSet;
import com.shimeta.model.RerankConfig;
import com.shimeta.rag.KnowledgeSourcePolicy;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.dto.RagEvalCaseRequest;
import com.shimeta.rag.dto.RagEvalRunRequest;
import com.shimeta.rag.dto.RetrievalTestRequest;
import com.shimeta.rag.engine.EngineType;
import com.shimeta.rag.engine.RetrievalResult;
import com.shimeta.rag.eval.RagEvalScorer;
import com.shimeta.rag.pipeline.ContextBudgetPruner;
import com.shimeta.repository.RagEvalCaseRepository;
import com.shimeta.repository.RagEvalRunRepository;
import com.shimeta.repository.RagEvalSetRepository;
import com.shimeta.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 知识库评测：用同一批题目比较不同检索配置能否找到正确内容。只衡量检索，不评判模型最终的回答。
 */
@Service
public class RagEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationService.class);

    public static final String DEFAULT_SET_NAME = "default-v1";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    static final int MAX_CASES = 200;
    private static final int MAX_RUNS_PER_KB = 50;
    private static final int MIN_TEXT_LENGTH = 2;
    private static final int RETRIEVE_TOP_K = 10;
    private static final int DEFAULT_TOP_K = 5;
    /** 名次指标要看到前 10 名，检索时不裁剪；能否交给模型另外按线上预算计算 */
    private static final int EVAL_MAX_CONTEXT_TOKENS = 100_000;
    private static final String RERANK_MODEL_MODE = "reranking_model";
    private static final Set<String> SEARCH_METHODS = Set.of("hybrid_search", "semantic_search", "full_text_search");
    private static final Set<String> RERANK_MODES = Set.of("weighted_score", RERANK_MODEL_MODE);

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagEvalSetRepository setRepository;
    private final RagEvalCaseRepository caseRepository;
    private final RagEvalRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final ContextBudgetPruner budgetPruner = new ContextBudgetPruner();
    private final Set<String> runningKnowledgeBases = ConcurrentHashMap.newKeySet();
    private Executor executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "rag-eval");
        thread.setDaemon(true);
        return thread;
    });
    private RerankConfigService rerankConfigService;

    public record RunView(String id, String knowledgeBaseId, String engine, String label, Object config,
                          String casesFingerprint, String status, Integer caseCount, Integer completedCount,
                          Integer errorCount, Integer rerankFallbackCount, String errorMessage,
                          Integer topK, Double hitAtTopK, Double hitAt5, Double recallAt10, Double ndcgAt10, Double avgLatencyMs,
                          LocalDateTime createdAt, Object details) {}

    public RagEvaluationService(KnowledgeBaseService knowledgeBaseService,
                                RagEvalSetRepository setRepository,
                                RagEvalCaseRepository caseRepository,
                                RagEvalRunRepository runRepository,
                                ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.setRepository = setRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Autowired(required = false)
    public void setRerankConfigService(RerankConfigService rerankConfigService) {
        this.rerankConfigService = rerankConfigService;
    }

    void setExecutor(Executor executor) {
        this.executor = executor;
    }

    // ==================== 题目 ====================

    @Transactional
    public RagEvalCase saveCase(String kbId, RagEvalCaseRequest req, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        RagEvalSet set = editableSet(kbId);
        requireCapacity(kbId, 1);
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setSetId(set.getId());
        evalCase.setKnowledgeBaseId(kbId);
        applyCase(evalCase, req);
        return caseRepository.save(evalCase);
    }

    /** 批量导入：有一题不合格就整批不保存，避免只导入一部分 */
    @Transactional
    public List<RagEvalCase> saveCases(String kbId, List<RagEvalCaseRequest> requests, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的题目");
        }
        RagEvalSet set = editableSet(kbId);
        requireCapacity(kbId, requests.size());
        List<RagEvalCase> cases = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            RagEvalCase evalCase = new RagEvalCase();
            evalCase.setSetId(set.getId());
            evalCase.setKnowledgeBaseId(kbId);
            try {
                applyCase(evalCase, requests.get(i));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 题：" + e.getMessage());
            }
            cases.add(evalCase);
        }
        return caseRepository.saveAll(cases);
    }

    @Transactional
    public RagEvalCase updateCase(String kbId, String caseId, RagEvalCaseRequest req, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        editableSet(kbId);
        RagEvalCase evalCase = findCase(kbId, caseId);
        applyCase(evalCase, req);
        return caseRepository.save(evalCase);
    }

    @Transactional
    public void deleteCase(String kbId, String caseId, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        editableSet(kbId);
        caseRepository.delete(findCase(kbId, caseId));
    }

    public List<RagEvalCase> listCases(String kbId, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        return caseRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
    }

    @Transactional
    public RagEvalSet freeze(String kbId, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseThrow(() -> new IllegalArgumentException("尚未创建评测集"));
        set.setFrozen(true);
        return setRepository.save(set);
    }

    private void applyCase(RagEvalCase evalCase, RagEvalCaseRequest req) {
        if (req == null || req.getQuery() == null || req.getQuery().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        List<String> texts = RagEvalScorer.cleanTexts(req.getExpectedTexts());
        for (String text : texts) {
            if (RagEvalScorer.normalize(text).length() < MIN_TEXT_LENGTH) {
                throw new IllegalArgumentException("答案原文「" + text + "」太短，至少 " + MIN_TEXT_LENGTH + " 个字符");
            }
        }
        String documentIds = joinIds(req.getExpectedDocumentIds());
        String chunkIds = joinIds(req.getExpectedChunkIds());
        if (texts.isEmpty() && documentIds == null && chunkIds == null) {
            throw new IllegalArgumentException("请填写答案原文，否则无法判断是否找到了正确内容");
        }
        evalCase.setQuery(req.getQuery().trim());
        evalCase.setExpectedTexts(texts.isEmpty() ? null : String.join("\n", texts));
        evalCase.setExpectedDocumentIds(documentIds);
        evalCase.setExpectedChunkIds(chunkIds);
    }

    private RagEvalSet editableSet(String kbId) {
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseGet(() -> {
                    RagEvalSet created = new RagEvalSet();
                    created.setKnowledgeBaseId(kbId);
                    created.setName(DEFAULT_SET_NAME);
                    created.setFrozen(false);
                    return setRepository.save(created);
                });
        if (Boolean.TRUE.equals(set.getFrozen())) {
            throw new IllegalArgumentException("评测集已冻结，不能再修改题目");
        }
        return set;
    }

    private void requireCapacity(String kbId, int adding) {
        long existing = caseRepository.countByKnowledgeBaseId(kbId);
        if (existing + adding > MAX_CASES) {
            throw new IllegalArgumentException("每个知识库最多 " + MAX_CASES + " 道题，当前已有 " + existing + " 道");
        }
    }

    private RagEvalCase findCase(String kbId, String caseId) {
        return caseRepository.findById(caseId)
                .filter(c -> kbId.equals(c.getKnowledgeBaseId()))
                .orElseThrow(() -> new IllegalArgumentException("评测题目不存在"));
    }

    // ==================== 运行 ====================

    /** 校验并创建运行记录后在后台执行：题目多时耗时较长，同步等待可能被网关超时中断 */
    public RunView startRun(String kbId, RagEvalRunRequest request, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseService.requireManageable(kbId, actor);
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseThrow(() -> new IllegalArgumentException("还没有评测题目，请先添加"));
        List<RagEvalCase> cases = caseRepository.findBySetIdOrderByCreatedAtAsc(set.getId());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("还没有评测题目，请先添加");
        }
        RagEvalRunRequest effective = resolveConfig(kb, request);
        String rerankModel = null;
        if (RERANK_MODEL_MODE.equals(effective.getRerankMode())) {
            knowledgeBaseService.requireRerankConfigured(effective.getRerankMode());
            rerankModel = rerankConfigService != null
                    ? rerankConfigService.getActiveConfig().map(RerankConfig::getModelName).orElse(null) : null;
        }
        if (!runningKnowledgeBases.add(kbId)) {
            throw new IllegalArgumentException("这个知识库有一次评测正在运行，请等它结束");
        }
        RagEvalRun run;
        try {
            run = new RagEvalRun();
            run.setSetId(set.getId());
            run.setKnowledgeBaseId(kbId);
            run.setEngine(KnowledgeSourcePolicy.engineOf(kb).name());
            run.setLabel(buildLabel(request, effective, rerankModel));
            run.setConfigJson(toJson(configMap(effective, rerankModel, kb)));
            run.setCasesFingerprint(fingerprint(cases));
            run.setStatus(STATUS_RUNNING);
            run.setCaseCount(cases.size());
            run = runRepository.save(run);
            final RagEvalRun started = run;
            executor.execute(() -> execute(started, cases, effective, actor));
        } catch (RuntimeException e) {
            runningKnowledgeBases.remove(kbId);
            throw e;
        }
        return toView(runRepository.findById(run.getId()).orElse(run), kb, true);
    }

    private void execute(RagEvalRun run, List<RagEvalCase> cases, RagEvalRunRequest config, CurrentActor actor) {
        try {
            double hitSum = 0, recallSum = 0, ndcgSum = 0;
            long latencySum = 0;
            int errors = 0, fallbacks = 0;
            List<Map<String, Object>> details = new ArrayList<>();
            for (RagEvalCase evalCase : cases) {
                Map<String, Object> row = evaluateCase(run.getKnowledgeBaseId(), evalCase, config, runTopK(parseJson(run.getConfigJson()), null), actor);
                details.add(row);
                hitSum += (double) row.get("hitAt5");
                recallSum += (double) row.get("recallAt10");
                ndcgSum += (double) row.get("ndcgAt10");
                latencySum += (long) row.get("latencyMs");
                errors += row.containsKey("error") ? 1 : 0;
                fallbacks += Boolean.TRUE.equals(row.get("rerankFallback")) ? 1 : 0;
                run.setCompletedCount(details.size());
                run = runRepository.save(run);
            }
            int n = cases.size();
            run.setHitAt5(round4(hitSum / n));
            run.setRecallAt10(round4(recallSum / n));
            run.setNdcgAt10(round4(ndcgSum / n));
            run.setAvgLatencyMs(Math.round((latencySum / (double) n) * 10.0) / 10.0);
            run.setErrorCount(errors);
            run.setRerankFallbackCount(fallbacks);
            run.setDetailJson(toJson(details));
            run.setStatus(STATUS_DONE);
            runRepository.save(run);
            pruneOldRuns(run.getKnowledgeBaseId());
        } catch (Exception e) {
            log.warn("知识库评测失败: kbId={}, err={}", run.getKnowledgeBaseId(), e.getMessage());
            run.setStatus(STATUS_FAILED);
            run.setErrorMessage(abbreviate(e.getMessage(), 500));
            runRepository.save(run);
        } finally {
            runningKnowledgeBases.remove(run.getKnowledgeBaseId());
        }
    }

    private Map<String, Object> evaluateCase(String kbId, RagEvalCase evalCase, RagEvalRunRequest config, int topK, CurrentActor actor) {
        Map<String, Predicate<RetrievedChunk>> targets = buildTargets(evalCase);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", evalCase.getId());
        row.put("query", evalCase.getQuery());
        long started = System.currentTimeMillis();
        List<RetrievedChunk> chunks = List.of();
        try {
            RetrievalResult result = knowledgeBaseService.testRetrieval(kbId, retrievalRequest(evalCase, config), actor);
            if (result != null && result.chunks() != null) {
                chunks = result.chunks();
            }
            if (result != null && result.metrics() != null) {
                row.put("rerankFallback", Boolean.TRUE.equals(result.metrics().get("rerankFallback")));
            }
        } catch (Exception e) {
            row.put("error", abbreviate(e.getMessage(), 200));
        }
        long latency = System.currentTimeMillis() - started;

        List<Set<String>> matchedByRank = new ArrayList<>();
        List<Map<String, Object>> top = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> matched = new HashSet<>();
            targets.forEach((name, predicate) -> {
                if (predicate.test(chunk)) {
                    matched.add(name);
                }
            });
            matchedByRank.add(matched);
            if (top.size() < 5) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", top.size() + 1);
                item.put("source", chunk.sourceName());
                item.put("snippet", abbreviate(chunk.rawContent() != null ? chunk.rawContent() : chunk.content(), 120));
                item.put("hit", !matched.isEmpty());
                top.add(item);
            }
        }
        RagEvalScorer.Score score = RagEvalScorer.score(matchedByRank, targets.size());
        // 和智能体线上一样：先取前 Top K 段，再按 Token 预算裁剪，剩下的才会交给模型
        List<RetrievedChunk> delivered = budgetPruner.prune(chunks.subList(0, Math.min(topK, chunks.size())),
                ContextBudgetPruner.DEFAULT_MAX_CONTEXT_TOKENS).prunedChunks();
        row.put("deliveredCount", delivered.size());
        row.put("reachesModel", delivered.stream().anyMatch(chunk -> targets.values().stream().anyMatch(t -> t.test(chunk))));
        row.put("hitAt5", score.hitAt5());
        row.put("recallAt10", round4(score.recallAt10()));
        row.put("ndcgAt10", round4(score.ndcgAt10()));
        row.put("firstHitRank", score.firstHitRank());
        row.put("targetCount", targets.size());
        row.put("matchedCount", score.matchedTargets().size());
        row.put("missed", targets.keySet().stream().filter(t -> !score.matchedTargets().contains(t)).toList());
        row.put("latencyMs", latency);
        row.put("top", top);
        return row;
    }

    /** 和智能体实际检索保持一致（开启问题改写、展开父块），但要看前 10 名且不走缓存 */
    private RetrievalTestRequest retrievalRequest(RagEvalCase evalCase, RagEvalRunRequest config) {
        RetrievalTestRequest req = new RetrievalTestRequest();
        req.setQuery(evalCase.getQuery());
        req.setTopK(RETRIEVE_TOP_K);
        req.setScoreThreshold(0.0);
        req.setCacheEnabled(false);
        req.setRewriteEnabled(true);
        req.setExpandParent(true);
        req.setMaxContextTokens(EVAL_MAX_CONTEXT_TOKENS);
        req.setSearchMethod(config.getSearchMethod());
        req.setRerankMode(config.getRerankMode());
        req.setRerankEnabled(RERANK_MODEL_MODE.equals(config.getRerankMode()));
        req.setVectorWeight(config.getVectorWeight());
        req.setKeywordWeight(config.getKeywordWeight());
        return req;
    }

    /** 目标名称直接展示给用户。优先用答案原文，它不受重建索引和改切分的影响 */
    private Map<String, Predicate<RetrievedChunk>> buildTargets(RagEvalCase evalCase) {
        Map<String, Predicate<RetrievedChunk>> targets = new LinkedHashMap<>();
        List<String> texts = RagEvalScorer.cleanTexts(
                evalCase.getExpectedTexts() != null ? List.of(evalCase.getExpectedTexts()) : List.of());
        for (String text : texts) {
            String wanted = RagEvalScorer.normalize(text);
            targets.put(text, chunk -> RagEvalScorer.normalize(chunk.content()).contains(wanted)
                    || RagEvalScorer.normalize(chunk.rawContent()).contains(wanted));
        }
        if (!targets.isEmpty()) {
            return targets;
        }
        for (String id : splitIds(evalCase.getExpectedDocumentIds())) {
            targets.put("文档 " + id, chunk -> id.equals(chunk.documentId()));
        }
        for (String id : splitIds(evalCase.getExpectedChunkIds())) {
            targets.put("切片 " + id, chunk -> id.equals(chunk.chunkId()));
        }
        return targets;
    }

    private RagEvalRunRequest resolveConfig(KnowledgeBase kb, RagEvalRunRequest request) {
        RagEvalRunRequest req = request != null ? request : new RagEvalRunRequest();
        RagEvalRunRequest out = new RagEvalRunRequest();
        boolean springAi = KnowledgeSourcePolicy.engineOf(kb) == EngineType.SPRING_AI;
        String searchMethod = notBlank(req.getSearchMethod()) ? req.getSearchMethod()
                : (notBlank(kb.getSearchMethod()) ? kb.getSearchMethod() : "hybrid_search");
        if (!SEARCH_METHODS.contains(searchMethod)) {
            throw new IllegalArgumentException("不支持的检索方式: " + searchMethod);
        }
        String rerankMode = notBlank(req.getRerankMode()) ? req.getRerankMode()
                : (Boolean.TRUE.equals(kb.getRerankEnabled()) ? KnowledgeBaseService.savedRerankMode(kb) : "weighted_score");
        if (!RERANK_MODES.contains(rerankMode)) {
            throw new IllegalArgumentException("不支持的排序方式: " + rerankMode);
        }
        if (!springAi) {
            rerankMode = "weighted_score";
        }
        double vector = req.getVectorWeight() != null ? req.getVectorWeight()
                : (kb.getVectorWeight() != null ? kb.getVectorWeight() : 0.5);
        double keyword = req.getKeywordWeight() != null ? req.getKeywordWeight()
                : (kb.getKeywordWeight() != null ? kb.getKeywordWeight() : 0.5);
        if (vector < 0 || keyword < 0 || vector > 1 || keyword > 1 || vector + keyword <= 0) {
            throw new IllegalArgumentException("权重要在 0 到 1 之间，且不能都是 0");
        }
        out.setSearchMethod(searchMethod);
        out.setRerankMode(rerankMode);
        out.setVectorWeight(vector);
        out.setKeywordWeight(keyword);
        return out;
    }

    private static Map<String, Object> configMap(RagEvalRunRequest config, String rerankModel, KnowledgeBase kb) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("topK", savedTopK(kb));
        map.put("contextBudget", ContextBudgetPruner.DEFAULT_MAX_CONTEXT_TOKENS);
        map.put("searchMethod", config.getSearchMethod());
        map.put("rerankMode", config.getRerankMode());
        map.put("rerankModel", rerankModel);
        map.put("vectorWeight", config.getVectorWeight());
        map.put("keywordWeight", config.getKeywordWeight());
        return map;
    }

    private static String buildLabel(RagEvalRunRequest request, RagEvalRunRequest config, String rerankModel) {
        if (request != null && notBlank(request.getLabel())) {
            return abbreviate(request.getLabel().trim(), 200);
        }
        String method = switch (config.getSearchMethod()) {
            case "semantic_search" -> "向量";
            case "full_text_search" -> "全文";
            default -> "混合";
        };
        if (RERANK_MODEL_MODE.equals(config.getRerankMode())) {
            return method + " · Rerank" + (rerankModel != null ? "（" + rerankModel + "）" : "");
        }
        if ("hybrid_search".equals(config.getSearchMethod())) {
            return method + " · 权重 " + Math.round(config.getVectorWeight() * 100) + ":" + Math.round(config.getKeywordWeight() * 100);
        }
        return method;
    }

    /** 题目变了分数就不能直接比较，页面靠这个指纹提示 */
    private static String fingerprint(List<RagEvalCase> cases) {
        String content = cases.stream()
                .sorted(Comparator.comparing(RagEvalCase::getId))
                .map(c -> c.getId() + "|" + c.getQuery() + "|" + c.getExpectedTexts() + "|" + c.getExpectedDocumentIds() + "|" + c.getExpectedChunkIds())
                .collect(Collectors.joining("\n"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    private void pruneOldRuns(String kbId) {
        List<RagEvalRun> runs = runRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
        if (runs.size() > MAX_RUNS_PER_KB) {
            runRepository.deleteAll(runs.subList(MAX_RUNS_PER_KB, runs.size()));
        }
    }

    // ==================== 运行记录 ====================

    public List<RunView> listRuns(String kbId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        return runRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::markInterrupted)
                .map(run -> toView(run, kb, false))
                .toList();
    }

    public RunView getRun(String kbId, String runId, CurrentActor actor) {
        KnowledgeBase kb = knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        return toView(markInterrupted(findRun(kbId, runId)), kb, true);
    }

    public void deleteRun(String kbId, String runId, CurrentActor actor) {
        knowledgeBaseService.requireManageable(kbId, actor);
        RagEvalRun run = findRun(kbId, runId);
        if (STATUS_RUNNING.equals(run.getStatus()) && runningKnowledgeBases.contains(kbId)) {
            throw new IllegalArgumentException("评测正在运行，结束后才能删除");
        }
        runRepository.delete(run);
    }

    /** 服务重启后，后台任务已经不在了，运行记录不能一直显示运行中 */
    private RagEvalRun markInterrupted(RagEvalRun run) {
        if (STATUS_RUNNING.equals(run.getStatus()) && !runningKnowledgeBases.contains(run.getKnowledgeBaseId())) {
            run.setStatus(STATUS_FAILED);
            run.setErrorMessage("服务重启，这次评测被中断");
            return runRepository.save(run);
        }
        return run;
    }

    private RagEvalRun findRun(String kbId, String runId) {
        return runRepository.findById(runId)
                .filter(r -> kbId.equals(r.getKnowledgeBaseId()))
                .orElseThrow(() -> new IllegalArgumentException("评测记录不存在"));
    }

    private RunView toView(RagEvalRun run, KnowledgeBase kb, boolean withDetails) {
        Object config = parseJson(run.getConfigJson());
        Object details = parseJson(run.getDetailJson());
        int topK = runTopK(config, kb);
        return new RunView(run.getId(), run.getKnowledgeBaseId(), run.getEngine(), run.getLabel(),
                config, run.getCasesFingerprint(), run.getStatus(), run.getCaseCount(),
                run.getCompletedCount(), run.getErrorCount(), run.getRerankFallbackCount(), run.getErrorMessage(),
                topK, hitAtTopK(details, topK), run.getHitAt5(), run.getRecallAt10(), run.getNdcgAt10(),
                run.getAvgLatencyMs(), run.getCreatedAt(), withDetails ? details : null);
    }

    /** 智能体实际拿到的段数。评测只取前 10 名，Top K 更大时也只能算到 10 */
    private static int savedTopK(KnowledgeBase kb) {
        int topK = kb != null && kb.getTopK() != null && kb.getTopK() > 0 ? kb.getTopK() : DEFAULT_TOP_K;
        return Math.min(topK, RETRIEVE_TOP_K);
    }

    /** 用运行时记录的 Top K，之后改知识库设置不影响旧分数；更早的记录没有保存，只能用当前设置 */
    private static int runTopK(Object config, KnowledgeBase kb) {
        if (config instanceof Map<?, ?> map && map.get("topK") instanceof Number recorded && recorded.intValue() > 0) {
            return Math.min(recorded.intValue(), RETRIEVE_TOP_K);
        }
        return savedTopK(kb);
    }

    /** 正确内容真正交给模型的题目占比：进入前 Top K 名，且没有被 Token 预算裁掉。更早的记录没有裁剪数据，只看名次 */
    private static Double hitAtTopK(Object details, int topK) {
        if (!(details instanceof List<?> rows) || rows.isEmpty()) {
            return null;
        }
        long hits = rows.stream()
                .filter(row -> row instanceof Map<?, ?> map && (map.get("reachesModel") instanceof Boolean reached
                        ? reached
                        : map.get("firstHitRank") instanceof Number rank && rank.intValue() <= topK))
                .count();
        return round4(hits / (double) rows.size());
    }

    /** 解析成 Map / List 再返回：接口的序列化器和这里的 ObjectMapper 不是同一个 Jackson 版本，不能直接返回 JsonNode */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评测结果序列化失败");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String joinIds(List<String> ids) {
        if (ids == null) {
            return null;
        }
        String joined = ids.stream().filter(RagEvaluationService::notBlank).map(String::trim).collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> splitIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+")).filter(s -> !s.isBlank()).toList();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
