package com.example.agentplatform.assistant;

import com.example.agentplatform.model.EmbeddingConfig;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.KnowledgeDocumentRepository;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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

    private KnowledgeDocument guideDoc(String sha) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId("doc-1");
        doc.setName(PlatformDocsService.GUIDE_FILE_NAME);
        doc.setSha256(sha);
        return doc;
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
    void createsSystemKnowledgeBaseAndUploadsBundledGuide() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of());
        when(knowledgeBaseService.createKnowledgeBase(any(), isNull())).thenReturn(docsKb);
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of());

        service.sync();

        assertTrue(service.isReady());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(knowledgeBaseService).uploadDocuments(eq("kb-docs"), files.capture(), any());
        MultipartFile file = files.getValue().get(0);
        assertEquals(PlatformDocsService.GUIDE_FILE_NAME, file.getOriginalFilename());
        assertArrayEquals(PlatformDocsService.readGuide(), file.getBytes());
    }

    @Test
    void unchangedGuideIsNotReuploaded() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        String sha = PlatformDocsService.sha256(PlatformDocsService.readGuide());
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of(guideDoc(sha)));

        service.sync();

        assertTrue(service.isReady());
        verify(knowledgeBaseService, never()).uploadDocuments(anyString(), any(), any());
        verify(knowledgeBaseService, never()).deleteDocument(anyString(), anyString(), any());
    }

    @Test
    void missingArchiveOnThisMachineTriggersReimport() throws Exception {
        ObjectStorageService storage = org.mockito.Mockito.mock(ObjectStorageService.class);
        service.setObjectStorageService(storage);
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        KnowledgeDocument doc = guideDoc(PlatformDocsService.sha256(PlatformDocsService.readGuide()));
        doc.setObjectKey("kb/kb-docs/docs/doc-1/platform-guide.md");
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of(doc));
        when(storage.exists("kb/kb-docs/docs/doc-1/platform-guide.md")).thenReturn(false);

        service.sync();

        verify(knowledgeBaseService).deleteDocument(eq("kb-docs"), eq("doc-1"), any());
        verify(knowledgeBaseService).uploadDocuments(eq("kb-docs"), any(), any());
        assertTrue(service.isReady());
    }

    @Test
    void presentArchiveAndSameContentSkipsReimport() throws Exception {
        ObjectStorageService storage = org.mockito.Mockito.mock(ObjectStorageService.class);
        service.setObjectStorageService(storage);
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        KnowledgeDocument doc = guideDoc(PlatformDocsService.sha256(PlatformDocsService.readGuide()));
        doc.setObjectKey("kb/kb-docs/docs/doc-1/platform-guide.md");
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of(doc));
        when(storage.exists(anyString())).thenReturn(true);

        service.sync();

        verify(knowledgeBaseService, never()).uploadDocuments(anyString(), any(), any());
    }

    @Test
    void changedGuideReplacesOnlyTheBundledDocument() throws Exception {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(docsKb));
        KnowledgeDocument adminDoc = new KnowledgeDocument();
        adminDoc.setId("doc-admin");
        adminDoc.setName("内部补充说明.md");
        when(documentRepository.findByKnowledgeBaseId("kb-docs")).thenReturn(List.of(guideDoc("old-sha"), adminDoc));

        service.sync();

        verify(knowledgeBaseService).deleteDocument(eq("kb-docs"), eq("doc-1"), any());
        verify(knowledgeBaseService, never()).deleteDocument(eq("kb-docs"), eq("doc-admin"), any());
        verify(knowledgeBaseService).uploadDocuments(eq("kb-docs"), any(), any());
    }

    @Test
    void bundledGuideExistsAndCoversKeyTopics() throws Exception {
        String guide = new String(PlatformDocsService.readGuide(), java.nio.charset.StandardCharsets.UTF_8);
        for (String topic : List.of("## 知识库", "## 模型网关", "## 开放与安全", "## AI 助手", "## 常见问题",
                "**运行中**、**空闲中**、**已停用**")) {
            assertTrue(guide.contains(topic), "缺少：" + topic);
        }
    }
}
