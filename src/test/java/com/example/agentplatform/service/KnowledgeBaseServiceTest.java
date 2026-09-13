package com.example.agentplatform.service;

import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.model.KnowledgeFaq;
import com.example.agentplatform.rag.KnowledgeBaseProvider;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.rag.dto.DifyDatasetDto;
import com.example.agentplatform.rag.dto.DifyDocumentDto;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentRepository;
import com.example.agentplatform.repository.KnowledgeFaqRepository;
import com.example.agentplatform.repository.ResourceGrantRepository;
import com.example.agentplatform.model.KnowledgeIndexVersion;
import com.example.agentplatform.model.KnowledgeSourceRevision;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.RetrievalTestRequest;
import com.example.agentplatform.rag.engine.RetrievalRequest;
import com.example.agentplatform.rag.engine.RetrievalResult;
import com.example.agentplatform.repository.KnowledgeIndexVersionRepository;
import com.example.agentplatform.repository.KnowledgeSourceRevisionRepository;
import com.example.agentplatform.storage.ObjectStorageService;
import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private KnowledgeFaqRepository faqRepository;

    @Mock
    private ResourceAuthorizationService resourceAuthorizationService;

    @Mock
    private ResourceGrantRepository resourceGrantRepository;

    @Mock
    private KnowledgeBaseProvider difyProvider;

    @Mock
    private OwnerNameResolver ownerNameResolver;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private KnowledgeSourceRevisionRepository sourceRevisionRepository;

    @Mock
    private KnowledgeIndexVersionRepository indexVersionRepository;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        CurrentActor.clear();
        when(difyProvider.getProviderType()).thenReturn("DIFY");
        lenient().when(resourceAuthorizationService.canViewKnowledgeBase(any(), any())).thenReturn(true);
        knowledgeBaseService = new KnowledgeBaseService(
                knowledgeBaseRepository,
                documentRepository,
                faqRepository,
                resourceAuthorizationService,
                resourceGrantRepository,
                List.of(difyProvider),
                new ObjectMapper(),
                ownerNameResolver,
                objectStorageService,
                sourceRevisionRepository,
                indexVersionRepository,
                null
        );
    }

    @AfterEach
    void tearDown() {
        CurrentActor.clear();
    }

    @Test
    void createKnowledgeBase_createsExternalDatasetAndPersistsLocal() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("企业售后知识库");
        req.setDescription("提供常见硬件与软件故障指引");
        req.setProvider("DIFY");

        DifyDatasetDto difyDto = new DifyDatasetDto();
        difyDto.setId("dify-ds-001");
        difyDto.setName("企业售后知识库");

        when(difyProvider.createDataset(eq("企业售后知识库"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(difyDto);
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(i -> {
            KnowledgeBase kb = i.getArgument(0);
            kb.setId("kb-test-1");
            return kb;
        });

        KnowledgeBase result = knowledgeBaseService.createKnowledgeBase(req);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("企业售后知识库");
        assertThat(result.getExternalDatasetId()).isEqualTo("dify-ds-001");
        assertThat(result.getProvider()).isEqualTo("DIFY");
        verify(knowledgeBaseRepository).save(any(KnowledgeBase.class));
    }

    @Test
    void uploadDocuments_validatesBatchCountAndFileExtensions() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setExternalDatasetId("dify-1");
        kb.setProvider("DIFY");

        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(resourceAuthorizationService.canManageKnowledgeBase(any(), any())).thenReturn(true);

        // 1. 测试超过 5 个文件限制
        List<MultipartFile> sixFiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sixFiles.add(new MockMultipartFile("files", "file" + i + ".pdf", "application/pdf", "content".getBytes()));
        }
        assertThatThrownBy(() -> knowledgeBaseService.uploadDocuments("kb-1", sixFiles))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每批最多允许上传 5 个文件");

        // 2. 测试不支持的文件格式 (如 .exe)
        List<MultipartFile> invalidExtFiles = List.of(
                new MockMultipartFile("files", "danger.exe", "application/octet-stream", "content".getBytes())
        );
        assertThatThrownBy(() -> knowledgeBaseService.uploadDocuments("kb-1", invalidExtFiles))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不受支持");
    }

    @Test
    void createFaq_persistsAndSyncsToProvider() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-faq-1");
        kb.setExternalDatasetId("dify-ds-faq");
        kb.setProvider("DIFY");

        when(knowledgeBaseRepository.findById("kb-faq-1")).thenReturn(Optional.of(kb));
        when(resourceAuthorizationService.canManageKnowledgeBase(any(), any())).thenReturn(true);

        DifyDocumentDto difyDoc = new DifyDocumentDto();
        difyDoc.setId("dify-doc-faq-999");
        when(difyProvider.syncFaqDocument(eq("dify-ds-faq"), any(), eq("如何重置密码？"), eq("请在个人设置页面点击重置密码。"), eq("账号管理")))
                .thenReturn(difyDoc);

        when(faqRepository.save(any(KnowledgeFaq.class))).thenAnswer(i -> {
            KnowledgeFaq f = i.getArgument(0);
            f.setId("faq-test-1");
            return f;
        });

        CreateFaqRequest req = new CreateFaqRequest();
        req.setQuestion("如何重置密码？");
        req.setAnswer("请在个人设置页面点击重置密码。");
        req.setCategory("账号管理");
        req.setContentType("TEXT");

        KnowledgeFaq result = knowledgeBaseService.createFaq("kb-faq-1", req);

        assertThat(result).isNotNull();
        assertThat(result.getQuestion()).isEqualTo("如何重置密码？");
        assertThat(result.getExternalDocId()).isEqualTo("dify-doc-faq-999");
        verify(faqRepository).save(any(KnowledgeFaq.class));
    }

    @Test
    void buildRetrievalContext_mergesRetrievedChunks() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("售后手册");
        kb.setEnabled(true);
        kb.setProvider("DIFY");
        kb.setExternalDatasetId("ds-1");

        when(knowledgeBaseRepository.findById("kb-1")).thenReturn(Optional.of(kb));
        when(difyProvider.retrieve(eq("ds-1"), any(RetrievalRequest.class))).thenReturn(List.of(
                new com.example.agentplatform.rag.RetrievedChunk("长按电源键 10 秒", "手册.pdf", 0.9)
        ));

        String context = knowledgeBaseService.buildRetrievalContext(List.of("kb-1"), "怎么重启设备");

        assertThat(context).contains("【知识库检索结果】");
        assertThat(context).contains("手册.pdf");
        assertThat(context).contains("长按电源键 10 秒");
    }

    @Test
    void createKnowledgeBase_unsupportedProvider_throwsIllegalArgumentException() {
        CreateKnowledgeBaseRequest req = new CreateKnowledgeBaseRequest();
        req.setName("测试未知引擎");
        req.setProvider("UNSUPPORTED_ENGINE");

        assertThatThrownBy(() -> knowledgeBaseService.createKnowledgeBase(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到支持的 RAG 知识库服务提供方");
    }

    @Test
    void displayHost_readsHostFromDifyBaseUrl() {
        assertThat(KnowledgeBaseService.displayHost("http://120.79.38.143/v1")).isEqualTo("120.79.38.143");
        assertThat(KnowledgeBaseService.displayHost("")).isEmpty();
    }

    @Test
    void getEngineInfo_usesProviderBaseUrl() {
        when(difyProvider.getBaseUrl()).thenReturn("http://120.79.38.143/v1");
        var info = knowledgeBaseService.getEngineInfo();
        assertThat(info.isConfigured()).isTrue();
        assertThat(info.getHost()).isEqualTo("120.79.38.143");
    }

    @Test
    void syncFromDify_reportsLocalDatasetsMissingOnCurrentEngine() {
        DifyDatasetDto remote = new DifyDatasetDto();
        remote.setId("ds-new");
        remote.setName("新实例知识库");
        when(difyProvider.listExternalDatasets()).thenReturn(List.of(remote));
        when(difyProvider.listDocuments("ds-new", 1, 100)).thenReturn(List.of());
        when(knowledgeBaseRepository.findByExternalDatasetId("ds-new")).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(i -> {
            KnowledgeBase kb = i.getArgument(0);
            if (kb.getId() == null) {
                kb.setId("kb-imported");
            }
            return kb;
        });
        when(documentRepository.countByKnowledgeBaseId(any())).thenReturn(0L);

        KnowledgeBase stale = new KnowledgeBase();
        stale.setId("kb-old");
        stale.setName("旧服务器知识库");
        stale.setProvider("DIFY");
        stale.setExternalDatasetId("ds-old");
        KnowledgeBase imported = new KnowledgeBase();
        imported.setId("kb-imported");
        imported.setName("新实例知识库");
        imported.setProvider("DIFY");
        imported.setExternalDatasetId("ds-new");
        when(knowledgeBaseRepository.findByProvider("DIFY")).thenReturn(List.of(stale, imported));

        Map<String, Object> result = knowledgeBaseService.syncFromDify();

        assertThat(result.get("importedKnowledgeBases")).isEqualTo(1);
        assertThat(result.get("staleCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> staleList = (List<Map<String, String>>) result.get("staleKnowledgeBases");
        assertThat(staleList).hasSize(1);
        assertThat(staleList.get(0).get("id")).isEqualTo("kb-old");
    }

    @Test
    void buildRetrievalContext_skipsDisabledOrMissingDataset() {
        KnowledgeBase disabled = new KnowledgeBase();
        disabled.setId("kb-off");
        disabled.setEnabled(false);
        disabled.setExternalDatasetId("ds-off");
        disabled.setProvider("DIFY");

        when(knowledgeBaseRepository.findById("kb-off")).thenReturn(Optional.of(disabled));

        String context = knowledgeBaseService.buildRetrievalContext(List.of("kb-off"), "任意问题");
        assertThat(context).isEmpty();
    }

    @Test
    void uploadDocuments_archivesFileToObjectStorageAndCreatesRevision() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-f8");
        kb.setName("快照测试库");
        kb.setProvider("DIFY");
        kb.setExternalDatasetId("ds-f8");
        when(knowledgeBaseRepository.findById("kb-f8")).thenReturn(Optional.of(kb));
        when(resourceAuthorizationService.canManageKnowledgeBase(any(), eq(kb))).thenReturn(true);

        DifyDocumentDto difyDoc = new DifyDocumentDto();
        difyDoc.setId("ext-doc-1");
        difyDoc.setIndexingStatus("completed");
        difyDoc.setWordCount(100L);
        difyDoc.setTokens(50L);
        when(difyProvider.uploadDocument(eq("ds-f8"), any())).thenReturn(difyDoc);

        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(i -> {
            KnowledgeDocument doc = i.getArgument(0);
            if (doc.getId() == null) {
                doc.setId("doc-f8-1");
            }
            return doc;
        });
        when(objectStorageService.putObject(anyString(), any(InputStream.class), anyLong(), any()))
                .thenReturn("abc123sha256hash");
        when(sourceRevisionRepository.save(any(KnowledgeSourceRevision.class))).thenAnswer(i -> {
            KnowledgeSourceRevision rev = i.getArgument(0);
            rev.setId("ksr-f8-1");
            return rev;
        });

        MockMultipartFile file = new MockMultipartFile("files", "guide.md", "text/markdown", "# Guide Content".getBytes());
        List<KnowledgeDocument> uploaded = knowledgeBaseService.uploadDocuments("kb-f8", List.of(file));

        assertThat(uploaded).hasSize(1);
        KnowledgeDocument doc = uploaded.get(0);
        assertThat(doc.getName()).isEqualTo("guide.md");
        assertThat(doc.getSha256()).isEqualTo("abc123sha256hash");
        assertThat(doc.getCurrentRevisionId()).isEqualTo("ksr-f8-1");
        assertThat(doc.getObjectKey()).contains("kb/kb-f8/docs/doc-f8-1/guide.md");
        verify(objectStorageService).putObject(anyString(), any(InputStream.class), eq(file.getSize()), eq("text/markdown"));
        verify(sourceRevisionRepository).save(any(KnowledgeSourceRevision.class));
    }

    @Test
    void testRetrieval_resolvesEngineAndReturnsEvidenceResult() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-test-recall");
        kb.setName("召回测试知识库");
        kb.setProvider("DIFY");
        kb.setExternalDatasetId("ds-recall");
        kb.setTopK(3);
        kb.setScoreThreshold(0.6);
        kb.setSearchMethod("hybrid_search");
        when(knowledgeBaseRepository.findById("kb-test-recall")).thenReturn(Optional.of(kb));

        RetrievedChunk chunk = RetrievedChunk.builder()
                .content("召回内容段落测试")
                .sourceName("测试规范.md")
                .score(0.89)
                .chunkId("seg-1")
                .tokenCount(64)
                .vectorScore(0.92)
                .keywordScore(0.81)
                .build();
        when(difyProvider.retrieve(eq("ds-recall"), any(RetrievalRequest.class)))
                .thenReturn(List.of(chunk));

        RetrievalTestRequest req = new RetrievalTestRequest();
        req.setQuery("如何进行故障排查？");
        req.setTopK(5);
        req.setScoreThreshold(0.4);

        RetrievalResult result = knowledgeBaseService.testRetrieval("kb-test-recall", req);

        assertThat(result.query()).isEqualTo("如何进行故障排查？");
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).content()).isEqualTo("召回内容段落测试");
        assertThat(result.chunks().get(0).score()).isEqualTo(0.89);
        assertThat(result.engineResolution().effectiveEngine().name()).isEqualTo("DIFY");
        assertThat(result.engineResolution().source()).isEqualTo("L2_KB_BINDING");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.metrics().get("topK")).isEqualTo(5);
        assertThat(result.metrics().get("totalHits")).isEqualTo(1);
    }

    @Test
    void getIndexVersions_autoInitializesV1WhenEmpty() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-v-init");
        kb.setName("版本初始化库");
        kb.setProvider("DIFY");
        kb.setExternalDatasetId("ds-v-init");
        kb.setSearchMethod("hybrid_search");
        kb.setTopK(3);
        when(knowledgeBaseRepository.findById("kb-v-init")).thenReturn(Optional.of(kb));
        when(indexVersionRepository.findByKnowledgeBaseIdOrderByVersionNoDesc("kb-v-init"))
                .thenReturn(List.of());
        when(indexVersionRepository.save(any(KnowledgeIndexVersion.class))).thenAnswer(i -> {
            KnowledgeIndexVersion v = i.getArgument(0);
            v.setId("kiv-v1");
            return v;
        });

        List<KnowledgeIndexVersion> versions = knowledgeBaseService.getIndexVersions("kb-v-init");

        assertThat(versions).hasSize(1);
        KnowledgeIndexVersion v1 = versions.get(0);
        assertThat(v1.getVersionNo()).isEqualTo(1);
        assertThat(v1.getStatus()).isEqualTo("READY");
        assertThat(v1.getEngineType()).isEqualTo("DIFY");
        verify(knowledgeBaseRepository).save(kb);
        assertThat(kb.getActiveIndexVersionId()).isEqualTo("kiv-v1");
    }
}
