package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.RagEvalCase;
import com.shimeta.model.RagEvalRun;
import com.shimeta.model.RagEvalSet;
import com.shimeta.model.RerankConfig;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.dto.RagEvalCaseRequest;
import com.shimeta.rag.dto.RagEvalRunRequest;
import com.shimeta.rag.dto.RetrievalTestRequest;
import com.shimeta.rag.engine.RetrievalResult;
import com.shimeta.repository.RagEvalCaseRepository;
import com.shimeta.repository.RagEvalRunRepository;
import com.shimeta.repository.RagEvalSetRepository;
import com.shimeta.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagEvaluationServiceTest {

    private static final String KB = "kb-1";

    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private RagEvalSetRepository setRepository;
    @Mock private RagEvalCaseRepository caseRepository;
    @Mock private RagEvalRunRepository runRepository;
    @Mock private RerankConfigService rerankConfigService;
    @Mock private CurrentActor actor;

    private final List<RagEvalCase> cases = new ArrayList<>();
    private RagEvaluationService service;
    private RagEvalRun savedRun;

    @BeforeEach
    void setUp() {
        service = new RagEvaluationService(knowledgeBaseService, setRepository, caseRepository, runRepository, new ObjectMapper());
        service.setExecutor(Runnable::run);
        service.setRerankConfigService(rerankConfigService);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB);
        kb.setProvider("SPRING_AI");
        kb.setSearchMethod("hybrid_search");
        kb.setRerankEnabled(true);
        kb.setRerankMode("weighted_score");
        kb.setVectorWeight(0.7);
        kb.setKeywordWeight(0.3);
        kb.setTopK(3);
        when(knowledgeBaseService.requireManageable(eq(KB), any())).thenReturn(kb);
        when(knowledgeBaseService.getKnowledgeBaseById(eq(KB), any())).thenReturn(kb);

        RagEvalSet set = new RagEvalSet();
        set.setId("set-1");
        set.setFrozen(false);
        when(setRepository.findFirstByKnowledgeBaseIdAndName(eq(KB), anyString())).thenReturn(Optional.of(set));
        when(caseRepository.findBySetIdOrderByCreatedAtAsc("set-1")).thenReturn(cases);
        when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(caseRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(runRepository.save(any())).thenAnswer(i -> {
            savedRun = i.getArgument(0);
            if (savedRun.getId() == null) {
                savedRun.setId("run-1");
            }
            return savedRun;
        });
        when(runRepository.findById("run-1")).thenAnswer(i -> Optional.ofNullable(savedRun));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(Object value) {
        return new ObjectMapper().valueToTree(value);
    }

    private RagEvalCase addCase(String id, String texts, String documentIds) {
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setId(id);
        evalCase.setQuery("问题" + id);
        evalCase.setExpectedTexts(texts);
        evalCase.setExpectedDocumentIds(documentIds);
        cases.add(evalCase);
        return evalCase;
    }

    private static RetrievedChunk chunk(String id, String documentId, String content) {
        return RetrievedChunk.builder().chunkId(id).documentId(documentId).content(content).rawContent(content).score(0.5).build();
    }

    private void retrievalReturns(Map<String, Object> metrics, RetrievedChunk... chunks) {
        when(knowledgeBaseService.testRetrieval(eq(KB), any(), any()))
                .thenReturn(RetrievalResult.of("q", List.of(chunks), null, 1L, metrics));
    }

    @Test
    void hitAtTopKUsesTheKnowledgeBaseTopK() {
        addCase("c1", "内容", null);
        addCase("c2", "内容", null);
        when(knowledgeBaseService.testRetrieval(eq(KB), any(), any()))
                .thenReturn(RetrievalResult.of("q", List.of(chunk("a", "d", "无关"), chunk("b", "d", "内容")), null, 1L, Map.of()))
                .thenReturn(RetrievalResult.of("q", List.of(chunk("a", "d", "无关"), chunk("b", "d", "无关"), chunk("c", "d", "无关"), chunk("e", "d", "内容")), null, 1L, Map.of()));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.topK()).isEqualTo(3);
        assertThat(view.hitAtTopK()).isEqualTo(0.5);
        assertThat(view.hitAt5()).isEqualTo(1.0);
    }

    @Test
    void oldRunUsesCurrentTopKAndNewRunKeepsTheRecordedOne() {
        RagEvalRun old = new RagEvalRun();
        old.setId("old");
        old.setKnowledgeBaseId(KB);
        old.setStatus(RagEvaluationService.STATUS_DONE);
        old.setDetailJson("[{\"firstHitRank\":2},{\"firstHitRank\":4},{\"firstHitRank\":null}]");
        RagEvalRun recorded = new RagEvalRun();
        recorded.setId("recorded");
        recorded.setKnowledgeBaseId(KB);
        recorded.setStatus(RagEvaluationService.STATUS_DONE);
        recorded.setConfigJson("{\"topK\":5}");
        recorded.setDetailJson("[{\"firstHitRank\":2},{\"firstHitRank\":4}]");
        when(runRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(KB)).thenReturn(List.of(old, recorded));

        List<RagEvaluationService.RunView> views = service.listRuns(KB, actor);

        assertThat(views.get(0).topK()).isEqualTo(3);
        assertThat(views.get(0).hitAtTopK()).isEqualTo(0.3333);
        assertThat(views.get(0).details()).isNull();
        assertThat(views.get(1).topK()).isEqualTo(5);
        assertThat(views.get(1).hitAtTopK()).isEqualTo(1.0);
    }

    @Test
    void viewUsesPlainCollectionsSoAnyJacksonVersionCanSerializeIt() {
        addCase("c1", "内容", null);
        retrievalReturns(Map.of(), chunk("a", "d", "内容"));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.config()).isInstanceOf(Map.class);
        assertThat(view.details()).isInstanceOf(List.class);
    }

    @Test
    void matchesAnswerTextIgnoringWhitespaceSoItSurvivesRechunking() {
        addCase("c1", "退款三个工作日到账", null);
        retrievalReturns(Map.of(), chunk("x", "d", "无关内容"), chunk("y", "d", "说明：退款三个\n工作日 到账。"));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.status()).isEqualTo(RagEvaluationService.STATUS_DONE);
        assertThat(view.hitAt5()).isEqualTo(1.0);
        assertThat(view.recallAt10()).isEqualTo(1.0);
        assertThat(json(view.details()).get(0).path("firstHitRank").asInt()).isEqualTo(2);
        assertThat(json(view.details()).get(0).path("top").get(1).path("hit").asBoolean()).isTrue();
    }

    @Test
    void documentTargetWithManyChunksNeverExceedsFullRecall() {
        addCase("c1", null, "doc-1");
        retrievalReturns(Map.of(), chunk("a", "doc-1", "一"), chunk("b", "doc-1", "二"), chunk("c", "doc-1", "三"));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.recallAt10()).isEqualTo(1.0);
        assertThat(view.ndcgAt10()).isEqualTo(1.0);
    }

    @Test
    void reportsMissedAnswersAndAveragesAcrossCases() {
        addCase("c1", "存在的内容\n缺失的内容", null);
        addCase("c2", "完全没有", null);
        retrievalReturns(Map.of(), chunk("a", "d", "存在的内容"));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.recallAt10()).isEqualTo(0.25);
        assertThat(view.hitAt5()).isEqualTo(0.5);
        assertThat(json(view.details()).get(0).path("missed").get(0).asText()).isEqualTo("缺失的内容");
    }

    @Test
    void runsWithRequestedConfigAndMatchesProductionRetrieval() {
        addCase("c1", "内容", null);
        retrievalReturns(Map.of(), chunk("a", "d", "内容"));
        RerankConfig config = new RerankConfig();
        config.setModelName("any-model");
        when(rerankConfigService.getActiveConfig()).thenReturn(Optional.of(config));
        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setRerankMode("reranking_model");

        RagEvaluationService.RunView view = service.startRun(KB, request, actor);

        ArgumentCaptor<RetrievalTestRequest> sent = ArgumentCaptor.forClass(RetrievalTestRequest.class);
        verify(knowledgeBaseService).testRetrieval(eq(KB), sent.capture(), any());
        assertThat(sent.getValue().getRerankEnabled()).isTrue();
        assertThat(sent.getValue().getRerankMode()).isEqualTo("reranking_model");
        assertThat(sent.getValue().getRewriteEnabled()).isTrue();
        assertThat(sent.getValue().getCacheEnabled()).isFalse();
        assertThat(view.label()).contains("any-model");
        assertThat(json(view.config()).path("rerankModel").asText()).isEqualTo("any-model");
    }

    @Test
    void defaultsToSavedConfigAndNeverTurnsOnRerankBySwitchAlone() {
        addCase("c1", "内容", null);
        retrievalReturns(Map.of(), chunk("a", "d", "内容"));

        RagEvaluationService.RunView view = service.startRun(KB, new RagEvalRunRequest(), actor);

        ArgumentCaptor<RetrievalTestRequest> sent = ArgumentCaptor.forClass(RetrievalTestRequest.class);
        verify(knowledgeBaseService).testRetrieval(eq(KB), sent.capture(), any());
        assertThat(sent.getValue().getRerankEnabled()).isFalse();
        assertThat(sent.getValue().getVectorWeight()).isEqualTo(0.7);
        assertThat(view.label()).isEqualTo("混合 · 权重 70:30");
    }

    @Test
    void rejectsRerankWithoutActiveConfig() {
        addCase("c1", "内容", null);
        doThrow(new IllegalArgumentException("尚未配置 Rerank 模型")).when(knowledgeBaseService).requireRerankConfigured("reranking_model");
        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setRerankMode("reranking_model");

        assertThatThrownBy(() -> service.startRun(KB, request, actor)).hasMessageContaining("Rerank");
        verify(runRepository, never()).save(any());
    }

    @Test
    void countsRerankFallbackSoResultIsNotMistakenForRerank() {
        addCase("c1", "内容", null);
        retrievalReturns(Map.of("rerankFallback", true), chunk("a", "d", "内容"));

        assertThat(service.startRun(KB, null, actor).rerankFallbackCount()).isEqualTo(1);
    }

    @Test
    void oneFailedCaseDoesNotStopTheRun() {
        addCase("c1", "内容", null);
        addCase("c2", "内容", null);
        when(knowledgeBaseService.testRetrieval(eq(KB), any(), any()))
                .thenThrow(new RuntimeException("向量接口超时"))
                .thenReturn(RetrievalResult.of("q", List.of(chunk("a", "d", "内容")), null, 1L, Map.of()));

        RagEvaluationService.RunView view = service.startRun(KB, null, actor);

        assertThat(view.status()).isEqualTo(RagEvaluationService.STATUS_DONE);
        assertThat(view.errorCount()).isEqualTo(1);
        assertThat(view.hitAt5()).isEqualTo(0.5);
    }

    @Test
    void secondRunIsRejectedWhileOneIsRunningAndAllowedAfterwards() {
        addCase("c1", "内容", null);
        retrievalReturns(Map.of(), chunk("a", "d", "内容"));
        List<Runnable> queued = new ArrayList<>();
        service.setExecutor(queued::add);

        service.startRun(KB, null, actor);
        assertThatThrownBy(() -> service.startRun(KB, null, actor)).hasMessageContaining("正在运行");

        queued.get(0).run();
        service.setExecutor(Runnable::run);
        assertThat(service.startRun(KB, null, actor).status()).isEqualTo(RagEvaluationService.STATUS_DONE);
    }

    @Test
    void runStuckAfterRestartIsReportedAsInterrupted() {
        RagEvalRun stuck = new RagEvalRun();
        stuck.setId("old");
        stuck.setKnowledgeBaseId(KB);
        stuck.setStatus(RagEvaluationService.STATUS_RUNNING);
        when(runRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(KB)).thenReturn(List.of(stuck));

        assertThat(service.listRuns(KB, actor).get(0).status()).isEqualTo(RagEvaluationService.STATUS_FAILED);
    }

    @Test
    void fingerprintChangesWhenAnswersChange() {
        RagEvalCase evalCase = addCase("c1", "内容", null);
        retrievalReturns(Map.of(), chunk("a", "d", "内容"));

        String before = service.startRun(KB, null, actor).casesFingerprint();
        assertThat(service.startRun(KB, null, actor).casesFingerprint()).isEqualTo(before);

        evalCase.setExpectedTexts("改过的内容");
        assertThat(service.startRun(KB, null, actor).casesFingerprint()).isNotEqualTo(before);
    }

    @Test
    void writesRequireManagePermission() {
        when(knowledgeBaseService.requireManageable(eq(KB), any())).thenThrow(new IllegalStateException("权限不足"));
        RagEvalCaseRequest req = new RagEvalCaseRequest();
        req.setQuery("问题");
        req.setExpectedTexts(List.of("答案"));

        assertThatThrownBy(() -> service.saveCase(KB, req, actor)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.startRun(KB, null, actor)).isInstanceOf(IllegalStateException.class);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void caseNeedsAnAnswerAndBatchIsAllOrNothing() {
        RagEvalCaseRequest good = new RagEvalCaseRequest();
        good.setQuery("问题");
        good.setExpectedTexts(List.of("答案原文"));
        RagEvalCaseRequest noAnswer = new RagEvalCaseRequest();
        noAnswer.setQuery("没有答案的问题");

        assertThatThrownBy(() -> service.saveCase(KB, noAnswer, actor)).hasMessageContaining("答案原文");
        assertThatThrownBy(() -> service.saveCases(KB, List.of(good, noAnswer), actor)).hasMessageContaining("第 2 题");
        verify(caseRepository, never()).saveAll(anyList());

        assertThat(service.saveCases(KB, List.of(good), actor).get(0).getExpectedTexts()).isEqualTo("答案原文");
    }

    @Test
    void rejectsTooManyCases() {
        when(caseRepository.countByKnowledgeBaseId(KB)).thenReturn((long) RagEvaluationService.MAX_CASES);
        RagEvalCaseRequest req = new RagEvalCaseRequest();
        req.setQuery("问题");
        req.setExpectedTexts(List.of("答案原文"));

        assertThatThrownBy(() -> service.saveCase(KB, req, actor)).hasMessageContaining("最多");
    }

    @Test
    void cannotTouchCaseOfAnotherKnowledgeBase() {
        RagEvalCase other = new RagEvalCase();
        other.setKnowledgeBaseId("kb-other");
        when(caseRepository.findById("c9")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.deleteCase(KB, "c9", actor)).hasMessageContaining("不存在");
        verify(caseRepository, never()).delete(any());
    }
}
