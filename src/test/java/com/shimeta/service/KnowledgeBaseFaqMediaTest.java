package com.shimeta.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.KnowledgeBase;
import com.shimeta.model.KnowledgeFaq;
import com.shimeta.rag.KnowledgeBaseProvider;
import com.shimeta.rag.RetrievedChunk;
import com.shimeta.rag.dto.CreateFaqRequest;
import com.shimeta.rag.dto.UpdateFaqRequest;
import com.shimeta.rag.engine.RetrievalRequest;
import com.shimeta.repository.KnowledgeBaseRepository;
import com.shimeta.repository.KnowledgeDocumentRepository;
import com.shimeta.repository.KnowledgeFaqRepository;
import com.shimeta.repository.KnowledgeIndexVersionRepository;
import com.shimeta.repository.KnowledgeSourceRevisionRepository;
import com.shimeta.repository.ResourceGrantRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseFaqMediaTest {

    private KnowledgeBaseRepository kbRepo;
    private KnowledgeFaqRepository faqRepo;
    private ResourceAuthorizationService authService;
    private KnowledgeBaseProvider provider;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        faqRepo = mock(KnowledgeFaqRepository.class);
        authService = mock(ResourceAuthorizationService.class);
        provider = mock(KnowledgeBaseProvider.class);
        when(provider.getProviderType()).thenReturn("SPRING_AI");

        service = new KnowledgeBaseService(
                kbRepo,
                mock(KnowledgeDocumentRepository.class),
                faqRepo,
                authService,
                mock(ResourceGrantRepository.class),
                List.of(provider),
                new ObjectMapper(),
                mock(OwnerNameResolver.class),
                mock(ObjectStorageService.class),
                mock(KnowledgeSourceRevisionRepository.class),
                mock(KnowledgeIndexVersionRepository.class),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Test
    void createFaqEnforcesImageAndVideoLimitsAndUrlValidation() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setProvider("SPRING_AI");
        when(kbRepo.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authService.canManageKnowledgeBase(any(), eq(kb))).thenReturn(true);
        when(faqRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 超过 9 张图片被拒绝
        CreateFaqRequest tooManyImages = new CreateFaqRequest();
        tooManyImages.setQuestion("Q1");
        tooManyImages.setAnswer("A1");
        tooManyImages.setImageUrls(IntStream.range(0, 10)
                .mapToObj(i -> "/uploads/knowledge/img_" + i + ".png")
                .toList());
        assertThrows(IllegalArgumentException.class, () -> service.createFaq("kb-1", tooManyImages, null));

        // 超过 3 个视频被拒绝
        CreateFaqRequest tooManyVideos = new CreateFaqRequest();
        tooManyVideos.setQuestion("Q2");
        tooManyVideos.setAnswer("A2");
        tooManyVideos.setVideoUrls(List.of(
                "https://cdn.example.com/1.mp4",
                "https://cdn.example.com/2.mp4",
                "https://cdn.example.com/3.mp4",
                "https://cdn.example.com/4.mp4"
        ));
        assertThrows(IllegalArgumentException.class, () -> service.createFaq("kb-1", tooManyVideos, null));

        // 非法协议（如 javascript:）被拒绝
        CreateFaqRequest badUrl = new CreateFaqRequest();
        badUrl.setQuestion("Q3");
        badUrl.setAnswer("A3");
        badUrl.setVideoUrls(List.of("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> service.createFaq("kb-1", badUrl, null));

        // 合法 9 图 + 3 视频（含站内与外部 CDN）允许保存并自动标记 MIXED
        CreateFaqRequest valid = new CreateFaqRequest();
        valid.setQuestion("如何重启设备？");
        valid.setAnswer("按电源键 5 秒。");
        valid.setImageUrls(IntStream.range(0, 9)
                .mapToObj(i -> "/uploads/knowledge/img_" + i + ".png")
                .toList());
        valid.setVideoUrls(List.of(
                "/uploads/knowledge/vid_1.mp4",
                "https://oss.example.com/guide/demo.mp4",
                "http://cdn.example.com/guide/demo2.webm"
        ));
        KnowledgeFaq saved = service.createFaq("kb-1", valid, null);
        assertEquals("MIXED", saved.getContentType());
        assertTrue(saved.getVideoUrls().contains("https://oss.example.com/guide/demo.mp4"));
    }

    @Test
    void updateFaqEnforcesVideoLimitAndClearsContentTypeWhenEmpty() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setProvider("SPRING_AI");
        KnowledgeFaq existing = new KnowledgeFaq();
        existing.setId("faq-1");
        existing.setKnowledgeBaseId("kb-1");
        existing.setQuestion("Q");
        existing.setAnswer("A");
        existing.setContentType("MIXED");
        existing.setVideoUrls("[\"/uploads/knowledge/vid_1.mp4\"]");

        when(kbRepo.findById("kb-1")).thenReturn(Optional.of(kb));
        when(authService.canManageKnowledgeBase(any(), eq(kb))).thenReturn(true);
        when(faqRepo.findForUpdate("faq-1")).thenReturn(Optional.of(existing));
        when(faqRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateFaqRequest clearReq = new UpdateFaqRequest();
        clearReq.setImageUrls(List.of());
        clearReq.setVideoUrls(List.of());
        KnowledgeFaq updated = service.updateFaq("kb-1", "faq-1", clearReq, null);
        assertEquals("TEXT", updated.getContentType());
        assertEquals("[]", updated.getVideoUrls());
    }

    @Test
    void saveFaqVideoValidatesFormatSizeAndRole() throws Exception {
        CurrentActor viewer = mock(CurrentActor.class);
        when(viewer.isViewer()).thenReturn(true);
        MockMultipartFile mp4 = new MockMultipartFile("file", "demo.mp4", "video/mp4", new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> service.saveFaqVideo(mp4, viewer));

        MockMultipartFile unsupported = new MockMultipartFile("file", "demo.avi", "video/x-msvideo", new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> service.saveFaqVideo(unsupported, null));

        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(KnowledgeBaseService.MAX_FAQ_VIDEO_SIZE_BYTES + 1L);
        when(oversized.getOriginalFilename()).thenReturn("huge.mp4");
        assertThrows(IllegalArgumentException.class, () -> service.saveFaqVideo(oversized, null));

        String savedUrl = service.saveFaqVideo(mp4, null);
        assertTrue(savedUrl.startsWith("/uploads/knowledge/vid_"));
        assertTrue(savedUrl.endsWith(".mp4"));
        Path savedFile = Paths.get(savedUrl.substring(1));
        assertTrue(Files.exists(savedFile));
        Files.deleteIfExists(savedFile);
    }

    @Test
    void buildRetrievalAndSearchSessionPropagateBothFaqImagesAndVideos() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setName("产品手册");
        kb.setProvider("SPRING_AI");
        kb.setExternalDatasetId("ds-1");
        kb.setEnabled(true);
        when(kbRepo.findById("kb-1")).thenReturn(Optional.of(kb));

        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1",
                "doc-faq-1",
                null,
                "FAQ-1",
                null,
                null,
                null,
                null,
                "问题：如何开机？\n答案：长按开机键。",
                "问题：如何开机？\n答案：长按开机键。",
                0.88,
                0.85,
                0.8,
                null,
                "HYBRID",
                20,
                Map.of("faqId", "doc-faq-1")
        );
        when(provider.retrieve(eq("ds-1"), any(RetrievalRequest.class))).thenReturn(List.of(chunk));

        KnowledgeFaq faq = new KnowledgeFaq();
        faq.setId("faq-1");
        faq.setKnowledgeBaseId("kb-1");
        faq.setExternalDocId("doc-faq-1");
        faq.setImageUrls("[\"/uploads/knowledge/img_1.png\",\"/uploads/knowledge/img_2.png\"]");
        faq.setVideoUrls("[\"/uploads/knowledge/vid_1.mp4\",\"https://cdn.example.com/guide.mp4\"]");
        when(faqRepo.findByExternalDocId("doc-faq-1")).thenReturn(Optional.of(faq));

        KnowledgeBaseService.RetrievalContext ctx = service.buildRetrieval(List.of("kb-1"), "怎么开机");
        assertEquals(List.of("/uploads/knowledge/img_1.png", "/uploads/knowledge/img_2.png"), ctx.faqImages());
        assertEquals(List.of("/uploads/knowledge/vid_1.mp4", "https://cdn.example.com/guide.mp4"), ctx.faqVideos());

        // 验证 KnowledgeSearchSession 同步记录图片与视频
        KnowledgeSearchSession session = new KnowledgeSearchSession(
                Map.of("kb-1", "产品手册"),
                (ids, q, budget) -> ctx
        );
        session.search("怎么开机", null);
        assertEquals(ctx.faqImages(), session.faqImages());
        assertEquals(ctx.faqVideos(), session.faqVideos());
    }
}
