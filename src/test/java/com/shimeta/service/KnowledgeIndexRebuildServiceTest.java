package com.shimeta.service;

import com.shimeta.model.EmbeddingConfig;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeDocument;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.rag.SpringAiKnowledgeBaseProvider;
import com.shimeta.repository.KnowledgeDocumentRepository;
import com.shimeta.repository.KnowledgeFaqRepository;
import com.shimeta.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeIndexRebuildServiceTest {
    private static final String KB_ID = "kb-rebuild";
    private final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeFaqRepository faqs = mock(KnowledgeFaqRepository.class);
    private final EmbeddingConfigService configs = mock(EmbeddingConfigService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final SpringAiKnowledgeBaseProvider provider = mock(SpringAiKnowledgeBaseProvider.class);
    private final KnowledgeIndexRebuildService rebuilds =
            new KnowledgeIndexRebuildService(knowledgeBases, documents, faqs, configs, storage, provider);
    private final KnowledgeBase kb = new KnowledgeBase();
    private final EmbeddingConfig embedding = new EmbeddingConfig();

    @BeforeEach
    void setUp() throws Exception {
        kb.setId(KB_ID);
        kb.setProvider("SPRING_AI");
        kb.setExternalDatasetId("dataset");
        embedding.setModelName("qwen3-embedding:0.6b");
        when(configs.getActiveConfig()).thenReturn(Optional.of(embedding));
        when(knowledgeBases.requireManageable(KB_ID, null)).thenReturn(kb);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setName("manual.txt");
        doc.setObjectKey("original");
        doc.setExternalDocId("doc-index");
        when(documents.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of(doc));
        when(storage.exists("original")).thenReturn(true);
        when(storage.getObject("original")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(provider.rebuildDocument(eq("dataset"), eq("doc-index"), eq("manual.txt"), any(byte[].class),
                same(embedding))).thenReturn(2);

        KnowledgeFaq faq = new KnowledgeFaq();
        faq.setId("faq-1");
        faq.setQuestion("如何安装？");
        when(faqs.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of(faq));
    }

    @Test
    void rebuildsDocumentsAndFaqsBeforeUpdatingTheModelLabel() {
        when(knowledgeBases.rebuildFaqIndex(kb, "faq-1", embedding)).thenReturn(true);

        var started = rebuilds.start(KB_ID, null);
        var completed = completed();

        assertEquals(2, started.total());
        assertEquals(2, completed.done());
        assertEquals(1, completed.rebuilt());
        assertEquals(1, completed.rebuiltFaqs());
        assertEquals(2, completed.chunks());
        var calls = inOrder(provider, knowledgeBases);
        calls.verify(provider).rebuildDocument(eq("dataset"), eq("doc-index"), eq("manual.txt"), any(byte[].class),
                same(embedding));
        calls.verify(knowledgeBases).rebuildFaqIndex(kb, "faq-1", embedding);
        calls.verify(knowledgeBases).updateRebuiltEmbeddingMetadata(KB_ID, embedding);
    }

    @Test
    void keepsThePreviousModelLabelIfFaqRebuildFails() {
        when(knowledgeBases.rebuildFaqIndex(kb, "faq-1", embedding))
                .thenThrow(new IllegalStateException("Embedding unavailable"));

        rebuilds.start(KB_ID, null);
        var completed = completed();

        assertEquals(1, completed.rebuilt());
        assertEquals(0, completed.rebuiltFaqs());
        assertEquals(1, completed.failed());
        verify(knowledgeBases, never()).updateRebuiltEmbeddingMetadata(any(), any());
    }

    private KnowledgeIndexRebuildService.Status completed() {
        await().atMost(Duration.ofSeconds(5)).until(() -> rebuilds.status(KB_ID, null).finishedAt() != null);
        return rebuilds.status(KB_ID, null);
    }
}
