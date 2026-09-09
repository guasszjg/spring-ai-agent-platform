package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.model.KnowledgeFaq;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.rag.dto.KnowledgeEngineInfo;
import com.example.agentplatform.rag.dto.UpdateFaqRequest;
import com.example.agentplatform.rag.dto.UpdateKnowledgeBaseRequest;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    private boolean checkAdmin() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && actor.isSuperAdmin();
    }

    // ==================== 知识库基础 CRUD ====================

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<KnowledgeBase>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        PageResult<KnowledgeBase> result = knowledgeBaseService.searchKnowledgeBases(keyword, provider, page, size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/engine")
    public ResponseEntity<ApiResponse<KnowledgeEngineInfo>> engine() {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeBaseService.getEngineInfo()));
    }

    @GetMapping("/{id:^(?!engine$).+}")
    public ResponseEntity<ApiResponse<KnowledgeBase>> getById(@PathVariable String id) {
        try {
            KnowledgeBase kb = knowledgeBaseService.getKnowledgeBaseById(id);
            return ResponseEntity.ok(ApiResponse.ok(kb));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeBase>> create(@RequestBody CreateKnowledgeBaseRequest req) {
        try {
            KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(req);
            return ResponseEntity.ok(ApiResponse.ok("知识库创建成功并已同步至 Dify RAG 引擎", kb));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<KnowledgeBase>> update(
            @PathVariable String id,
            @RequestBody UpdateKnowledgeBaseRequest req) {
        try {
            KnowledgeBase kb = knowledgeBaseService.updateKnowledgeBase(id, req);
            return ResponseEntity.ok(ApiResponse.ok("知识库信息更新成功", kb));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        try {
            knowledgeBaseService.deleteKnowledgeBase(id);
            return ResponseEntity.ok(ApiResponse.ok("知识库已彻底删除", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/sync-from-dify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncFromDify() {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可全量同步外部知识库"));
        }
        try {
            Map<String, Object> result = knowledgeBaseService.syncFromDify();
            return ResponseEntity.ok(ApiResponse.ok("Dify 知识库数据同步完成", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 文档库文件管理 ====================

    @PostMapping("/{id}/documents/upload")
    public ResponseEntity<ApiResponse<List<KnowledgeDocument>>> uploadDocuments(
            @PathVariable String id,
            @RequestParam("files") List<MultipartFile> files) {
        try {
            List<KnowledgeDocument> uploaded = knowledgeBaseService.uploadDocuments(id, files);
            return ResponseEntity.ok(ApiResponse.ok("已成功上传 " + uploaded.size() + " 个文档至知识库", uploaded));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<ApiResponse<PageResult<KnowledgeDocument>>> listDocuments(
            @PathVariable String id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PageResult<KnowledgeDocument> result = knowledgeBaseService.searchDocuments(id, keyword, status, page, size);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/documents/{docId}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @PathVariable String id,
            @PathVariable String docId) {
        try {
            knowledgeBaseService.deleteDocument(id, docId);
            return ResponseEntity.ok(ApiResponse.ok("文档已成功删除", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/documents/{docId}/refresh")
    public ResponseEntity<ApiResponse<KnowledgeDocument>> refreshDocument(
            @PathVariable String id,
            @PathVariable String docId) {
        try {
            KnowledgeDocument doc = knowledgeBaseService.refreshDocumentStatus(id, docId);
            return ResponseEntity.ok(ApiResponse.ok("文档状态刷新成功", doc));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 问答对 (FAQ) 管理 ====================

    @GetMapping("/{id}/faqs")
    public ResponseEntity<ApiResponse<PageResult<KnowledgeFaq>>> listFaqs(
            @PathVariable String id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PageResult<KnowledgeFaq> result = knowledgeBaseService.searchFaqs(id, keyword, category, page, size);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/faqs/categories")
    public ResponseEntity<ApiResponse<List<String>>> listFaqCategories(@PathVariable String id) {
        try {
            List<String> categories = knowledgeBaseService.getFaqCategories(id);
            return ResponseEntity.ok(ApiResponse.ok(categories));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/faqs")
    public ResponseEntity<ApiResponse<KnowledgeFaq>> createFaq(
            @PathVariable String id,
            @RequestBody CreateFaqRequest req) {
        try {
            KnowledgeFaq faq = knowledgeBaseService.createFaq(id, req);
            return ResponseEntity.ok(ApiResponse.ok("FAQ 问答创建成功并已向量化", faq));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/faqs/{faqId}")
    public ResponseEntity<ApiResponse<KnowledgeFaq>> updateFaq(
            @PathVariable String id,
            @PathVariable String faqId,
            @RequestBody UpdateFaqRequest req) {
        try {
            KnowledgeFaq faq = knowledgeBaseService.updateFaq(id, faqId, req);
            return ResponseEntity.ok(ApiResponse.ok("FAQ 问答更新成功", faq));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/faqs/{faqId}")
    public ResponseEntity<ApiResponse<Void>> deleteFaq(
            @PathVariable String id,
            @PathVariable String faqId) {
        try {
            knowledgeBaseService.deleteFaq(id, faqId);
            return ResponseEntity.ok(ApiResponse.ok("FAQ 问答已删除", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 图片上传辅助 ====================

    @PostMapping("/upload-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String url = knowledgeBaseService.saveFaqImage(file);
            return ResponseEntity.ok(ApiResponse.ok("图片上传成功", Map.of("url", url)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
