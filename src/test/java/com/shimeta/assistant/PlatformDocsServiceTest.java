package com.shimeta.assistant;

import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.model.UserRole;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeDocumentRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.EmbeddingConfigService;
import com.shimeta.service.KnowledgeBaseService;
import com.shimeta.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformDocsServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock EmbeddingConfigService embeddingConfigService;

    private PlatformDocsService service;
    private KnowledgeBase docsKb;

    @BeforeEach
    void setUp() {
        service = new PlatformDocsService(knowledgeBaseService, knowledgeBaseRepository, documentRepository, embeddingConfigService);
        docsKb = new KnowledgeBase();
        docsKb.setId("kb-docs");
        docsKb.setName(PlatformDocsService.KB_NAME);
        docsKb.setIsSystem(true);
        docsKb.setProvider("SPRING_AI");
        when(embeddingConfigService.getActiveConfig()).thenReturn(Optional.of(new EmbeddingConfig()));
    }

    private List<KnowledgeDocument> allCurrentDocs() throws Exception {
        List<KnowledgeDocument> list = new ArrayList<>();
        int idx = 1;
        for (PlatformDocsService.DocSpec spec : PlatformDocsService.bundledDocs()) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId("doc-" + idx++);
            doc.setName(spec.fileName());
            doc.setSha256(PlatformDocsService.sha256(PlatformDocsService.readDoc(spec)));
            doc.setObjectKey("kb/kb-docs/docs/" + doc.getId() + "/" + spec.fileName());
            list.add(doc);
        }
        return list;
    }

    @Test
    void skipsImportWithoutEmbeddingModel() throws Exception {
        when(embeddingConfigService.getActiveConfig()).thenReturn(Optional.empty());

        service.sync();

        assertFalse(service.isReady());
        verify(knowledgeBaseService, never()).createKnowledgeBase(any(), any());
        assertTrue(service.search("怎么创建知识库", 3).isEmpty());
    }

    @Test
    void createsSystemKnowledgeBaseAndUploadsAllBundledDocs() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of());
        when(knowledgeBaseService.createKnowledgeBase(any(), isNull())).thenReturn(docsKb);
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of());

        service.sync();

        assertTrue(service.isReady());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(knowledgeBaseService, times(PlatformDocsService.bundledDocs().size()))
                .uploadDocuments(eq("kb-docs"), files.capture(), any());
        List<String> uploadedNames = files.getAllValues().stream()
                .map(list -> list.get(0).getOriginalFilename())
                .toList();
        assertEquals(PlatformDocsService.bundledDocs().stream().map(PlatformDocsService.DocSpec::fileName).toList(),
                uploadedNames);
        assertArrayEquals(PlatformDocsService.readGuide(), files.getAllValues().get(0).get(0).getBytes());
    }

    @Test
    void unchangedDocsAreNotReuploaded() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(allCurrentDocs());

        service.sync();

        assertTrue(service.isReady());
        verify(knowledgeBaseService, never()).uploadDocuments(anyString(), any(), any());
        verify(knowledgeBaseService, never()).deleteDocument(anyString(), anyString(), any());
    }

    @Test
    void missingArchiveOnThisMachineTriggersReimportOnlyForAffectedDoc() throws Exception {
        ObjectStorageService storage = org.mockito.Mockito.mock(ObjectStorageService.class);
        service.setObjectStorageService(storage);
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        List<KnowledgeDocument> docs = allCurrentDocs();
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(docs);
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.exists(docs.get(0).getObjectKey())).thenReturn(false);

        service.sync();

        verify(knowledgeBaseService, times(1)).deleteDocument(eq("kb-docs"), eq(docs.get(0).getId()), any());
        verify(knowledgeBaseService, times(1)).uploadDocuments(eq("kb-docs"), any(), any());
        assertTrue(service.isReady());
    }

    @Test
    void presentArchiveAndSameContentSkipsReimport() throws Exception {
        ObjectStorageService storage = org.mockito.Mockito.mock(ObjectStorageService.class);
        service.setObjectStorageService(storage);
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(allCurrentDocs());
        when(storage.exists(anyString())).thenReturn(true);

        service.sync();

        verify(knowledgeBaseService, never()).uploadDocuments(anyString(), any(), any());
    }

    @Test
    void changedSingleDocReplacesOnlyThatDocAndKeepsAdminUploads() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        List<KnowledgeDocument> docs = allCurrentDocs();
        docs.get(0).setSha256("old-sha"); // 仅 platform-guide.md 内容变动
        KnowledgeDocument adminDoc = new KnowledgeDocument();
        adminDoc.setId("doc-admin");
        adminDoc.setName("内部补充说明.md");
        docs.add(adminDoc);
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(docs);

        service.sync();

        verify(knowledgeBaseService, times(1)).deleteDocument(eq("kb-docs"), eq(docs.get(0).getId()), any());
        verify(knowledgeBaseService, never()).deleteDocument(eq("kb-docs"), eq("doc-admin"), any());
        verify(knowledgeBaseService, times(1)).uploadDocuments(eq("kb-docs"), any(), any());
    }

    @Test
    void deployOpsDocIsOnlyRetrievableBySuperAdmin() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(allCurrentDocs());
        service.sync();

        RetrievedChunk opsChunk = new RetrievedChunk("APP_CRYPTO_SECRET 说明", PlatformDocsService.OPS_FILE_NAME, 0.92);
        RetrievedChunk guideChunk = new RetrievedChunk("知识库使用说明", PlatformDocsService.GUIDE_FILE_NAME, 0.88);
        when(knowledgeBaseService.retrieveChunks(eq("kb-docs"), anyString(), anyInt()))
                .thenReturn(List.of(opsChunk, guideChunk));

        CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
        CurrentActor admin = new CurrentActor("u-admin", "admin", UserRole.SUPER_ADMIN);

        List<RetrievedChunk> devResults = service.search("如何配置环境变量", 2, dev);
        assertEquals(1, devResults.size());
        assertEquals(PlatformDocsService.GUIDE_FILE_NAME, devResults.get(0).sourceName());

        List<RetrievedChunk> adminResults = service.search("如何配置环境变量", 2, admin);
        assertEquals(2, adminResults.size());
        assertEquals(PlatformDocsService.OPS_FILE_NAME, adminResults.get(0).sourceName());
    }

    @Test
    void bundledDocsExistAndCoverKeyTopicsConfigDetailsAndErrors() throws Exception {
        Map<String, String> allDocs = PlatformDocsService.readAllBundledDocs();
        assertEquals(5, allDocs.size());

        String guide = allDocs.get(PlatformDocsService.GUIDE_FILE_NAME);
        for (String topic : List.of("## 知识库", "## 模型网关", "## 开放与安全", "## AI 助手", "## 常见问题",
                "## 配置项详细说明", "**运行中**、**空闲中**、**已停用**", "温度", "记忆轮数", "Top K", "上下文窗口")) {
            assertTrue(guide.contains(topic), "platform-guide.md 缺少：" + topic);
        }

        String errors = allDocs.get("error-dictionary.md");
        for (String code : List.of("key_invalid", "agent_not_running", "rate_limited", "Connect timed out", "系统公共预置资产受平台保护")) {
            assertTrue(errors.contains(code), "error-dictionary.md 缺少：" + code);
        }
    }
}
