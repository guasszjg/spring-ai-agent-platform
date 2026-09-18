package com.example.agentplatform.service;

import com.example.agentplatform.model.RagEvalCase;
import com.example.agentplatform.model.RagEvalRun;
import com.example.agentplatform.model.RagEvalSet;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.RetrievalTestRequest;
import com.example.agentplatform.rag.engine.RetrievalResult;
import com.example.agentplatform.rag.eval.RetrievalMetrics;
import com.example.agentplatform.repository.RagEvalCaseRepository;
import com.example.agentplatform.repository.RagEvalRunRepository;
import com.example.agentplatform.repository.RagEvalSetRepository;
import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RagEvaluationService {

    public static final String DEFAULT_SET_NAME = "default-v1";

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagEvalSetRepository setRepository;
    private final RagEvalCaseRepository caseRepository;
    private final RagEvalRunRepository runRepository;
    private final ObjectMapper objectMapper;

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

    @Transactional
    public RagEvalCase saveCase(String kbId, String query, List<String> expectedChunkIds,
                                List<String> expectedDocumentIds, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("评测 query 不能为空");
        }
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseGet(() -> {
                    RagEvalSet created = new RagEvalSet();
                    created.setKnowledgeBaseId(kbId);
                    created.setName(DEFAULT_SET_NAME);
                    created.setFrozen(false);
                    return setRepository.save(created);
                });
        if (Boolean.TRUE.equals(set.getFrozen())) {
            throw new IllegalStateException("评测集已冻结，不能再写入用例");
        }
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setSetId(set.getId());
        evalCase.setKnowledgeBaseId(kbId);
        evalCase.setQuery(query.trim());
        evalCase.setExpectedChunkIds(joinIds(expectedChunkIds));
        evalCase.setExpectedDocumentIds(joinIds(expectedDocumentIds));
        return caseRepository.save(evalCase);
    }

    public List<RagEvalCase> listCases(String kbId, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        return caseRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
    }

    @Transactional
    public RagEvalSet freeze(String kbId, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseThrow(() -> new IllegalArgumentException("尚未创建评测集"));
        set.setFrozen(true);
        return setRepository.save(set);
    }

    @Transactional
    public RagEvalRun run(String kbId, String engine, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        RagEvalSet set = setRepository.findFirstByKnowledgeBaseIdAndName(kbId, DEFAULT_SET_NAME)
                .orElseThrow(() -> new IllegalArgumentException("尚未创建评测集，请先保存评测用例"));
        List<RagEvalCase> cases = caseRepository.findBySetIdOrderByCreatedAtAsc(set.getId());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评测集为空");
        }
        String engineName = engine == null || engine.isBlank() ? "SPRING_AI" : engine.trim().toUpperCase();

        double hitSum = 0;
        double recallSum = 0;
        double ndcgSum = 0;
        long latencySum = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            RetrievalTestRequest req = new RetrievalTestRequest();
            req.setQuery(evalCase.getQuery());
            req.setTopK(10);
            req.setScoreThreshold(0.0);
            req.setEngineOverride(engineName);
            req.setCacheEnabled(false);
            req.setRerankEnabled(false);
            long started = System.currentTimeMillis();
            RetrievalResult result = knowledgeBaseService.testRetrieval(kbId, req, actor);
            long latency = System.currentTimeMillis() - started;
            latencySum += latency;

            Set<String> expected = expectedIds(evalCase);
            List<String> retrieved = new ArrayList<>();
            if (result != null && result.chunks() != null) {
                for (RetrievedChunk chunk : result.chunks()) {
                    if (chunk.chunkId() != null && expected.contains(chunk.chunkId())) {
                        retrieved.add(chunk.chunkId());
                    } else if (chunk.documentId() != null && expected.contains(chunk.documentId())) {
                        retrieved.add(chunk.documentId());
                    } else if (chunk.chunkId() != null) {
                        retrieved.add(chunk.chunkId());
                    }
                }
            }
            double hit = RetrievalMetrics.hitAtK(expected, retrieved, 5);
            double recall = RetrievalMetrics.recallAtK(expected, retrieved, 10);
            double ndcg = RetrievalMetrics.ndcgAtK(expected, retrieved, 10);
            hitSum += hit;
            recallSum += recall;
            ndcgSum += ndcg;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", evalCase.getId());
            row.put("query", evalCase.getQuery());
            row.put("hitAt5", hit);
            row.put("recallAt10", recall);
            row.put("ndcgAt10", ndcg);
            row.put("latencyMs", latency);
            details.add(row);
        }

        int n = cases.size();
        RagEvalRun run = new RagEvalRun();
        run.setSetId(set.getId());
        run.setKnowledgeBaseId(kbId);
        run.setEngine(engineName);
        run.setCaseCount(n);
        run.setHitAt5(round4(hitSum / n));
        run.setRecallAt10(round4(recallSum / n));
        run.setNdcgAt10(round4(ndcgSum / n));
        run.setAvgLatencyMs(Math.round((latencySum / (double) n) * 10.0) / 10.0);
        try {
            run.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (Exception e) {
            run.setDetailJson("[]");
        }
        return runRepository.save(run);
    }

    public List<RagEvalRun> listRuns(String kbId, CurrentActor actor) {
        knowledgeBaseService.getKnowledgeBaseById(kbId, actor);
        return runRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
    }

    private static String joinIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).collect(Collectors.joining(","));
    }

    private static Set<String> expectedIds(RagEvalCase evalCase) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(splitIds(evalCase.getExpectedChunkIds()));
        ids.addAll(splitIds(evalCase.getExpectedDocumentIds()));
        return ids;
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
