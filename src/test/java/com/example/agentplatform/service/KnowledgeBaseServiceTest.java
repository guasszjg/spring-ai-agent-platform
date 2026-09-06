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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private KnowledgeBaseProvider difyProvider;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        when(difyProvider.getProviderType()).thenReturn("DIFY");
        knowledgeBaseService = new KnowledgeBaseService(
                knowledgeBaseRepository,
                documentRepository,
                faqRepository,
                List.of(difyProvider),
                new ObjectMapper()
        );
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

        when(difyProvider.createDataset(eq("企业售后知识库"), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(difyDto);
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
}
