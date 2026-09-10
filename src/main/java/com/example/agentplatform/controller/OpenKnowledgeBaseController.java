package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.KnowledgeDocument;
import com.example.agentplatform.model.KnowledgeFaq;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.rag.dto.CreateFaqRequest;
import com.example.agentplatform.rag.dto.CreateKnowledgeBaseRequest;
import com.example.agentplatform.rag.dto.UpdateFaqRequest;
import com.example.agentplatform.rag.dto.UpdateKnowledgeBaseRequest;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.security.OpenApiScopes;
import com.example.agentplatform.service.AuditRecorder;
import com.example.agentplatform.service.KnowledgeBaseService;
import com.example.agentplatform.service.ResourceAuthorizationService;
import com.example.agentplatform.service.UsageRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/open/v1/knowledge-bases")
public class OpenKnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ResourceAuthorizationService authorizationService;
    private final AuditRecorder auditRecorder;
    private final UsageRecorder usageRecorder;

    public OpenKnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                       KnowledgeBaseRepository knowledgeBaseRepository,
                                       ResourceAuthorizationService authorizationService,
                                       AuditRecorder auditRecorder,
                                       UsageRecorder usageRecorder) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.authorizationService = authorizationService;
        this.auditRecorder = auditRecorder;
        this.usageRecorder = usageRecorder;
    }

    @GetMapping
    public ResponseEntity<?> listKnowledgeBases() {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:read 权限");
        }
        List<KnowledgeBase> kbs = knowledgeBaseService.searchKnowledgeBases(null, null, 1, 1000, ctx.asActor(), null).getRecords();
        usageRecorder.record("kb.list", null, null, 200, null, 10, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(kbs));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getKnowledgeBase(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:read 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null) {
            return error(HttpStatus.NOT_FOUND, "kb_not_found", "知识库不存在");
        }
        if (!authorizationService.canViewKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权访问该知识库");
        }
        usageRecorder.record("kb.get", null, null, 200, null, 5, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(kb));
    }

    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        try {
            KnowledgeBase created = knowledgeBaseService.createKnowledgeBase(request);
            auditRecorder.record("kb.create", "KNOWLEDGE_BASE", created.getId(), "SUCCESS", null, "LOW", created.getName());
            usageRecorder.record("kb.create", null, null, 200, null, 15, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("创建成功", created));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateKnowledgeBase(@PathVariable String id, @RequestBody UpdateKnowledgeBaseRequest request) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase existing = knowledgeBaseRepository.findById(id).orElse(null);
        if (existing == null) {
            return error(HttpStatus.NOT_FOUND, "kb_not_found", "知识库不存在");
        }
        if (!authorizationService.canManageKnowledgeBase(ctx.asActor(), existing)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权修改该知识库");
        }
        try {
            KnowledgeBase updated = knowledgeBaseService.updateKnowledgeBase(id, request);
            auditRecorder.record("kb.update", "KNOWLEDGE_BASE", id, "SUCCESS", null, "LOW", updated.getName());
            usageRecorder.record("kb.update", null, null, 200, null, 15, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("更新成功", updated));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteKnowledgeBase(@PathVariable String id) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase existing = knowledgeBaseRepository.findById(id).orElse(null);
        if (existing == null) {
            return error(HttpStatus.NOT_FOUND, "kb_not_found", "知识库不存在");
        }
        if (!authorizationService.canManageKnowledgeBase(ctx.asActor(), existing)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权删除该知识库");
        }
        try {
            knowledgeBaseService.deleteKnowledgeBase(id);
            auditRecorder.record("kb.delete", "KNOWLEDGE_BASE", id, "SUCCESS", null, "MEDIUM", existing.getName());
            usageRecorder.record("kb.delete", null, null, 200, null, 15, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("删除成功", Map.of("id", id)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "kb_error", e.getMessage());
        }
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null) {
            return error(HttpStatus.NOT_FOUND, "kb_not_found", "知识库不存在");
        }
        if (!authorizationService.canManageKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权向该知识库上传文档");
        }
        try {
            List<KnowledgeDocument> uploaded = knowledgeBaseService.uploadDocuments(id, List.of(file));
            auditRecorder.record("kb.doc_upload", "KNOWLEDGE_BASE", id, "SUCCESS", null, "LOW", file.getOriginalFilename());
            usageRecorder.record("kb.doc_upload", null, null, 200, null, 50, 0, 0, null);
            return ResponseEntity.ok(ApiResponse.ok("上传成功", uploaded));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "upload_failed", e.getMessage());
        }
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<?> listDocuments(@PathVariable String id,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:read 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null) {
            return error(HttpStatus.NOT_FOUND, "kb_not_found", "知识库不存在");
        }
        if (!authorizationService.canViewKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权查看该知识库文档");
        }
        return ResponseEntity.ok(ApiResponse.ok(knowledgeBaseService.searchDocuments(id, null, null, page, size)));
    }

    @DeleteMapping("/{id}/documents/{docId}")
    public ResponseEntity<?> deleteDocument(@PathVariable String id, @PathVariable String docId) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canManageKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权删除该文档");
        }
        knowledgeBaseService.deleteDocument(id, docId);
        auditRecorder.record("kb.doc_delete", "KNOWLEDGE_BASE", id, "SUCCESS", null, "LOW", docId);
        return ResponseEntity.ok(ApiResponse.ok("文档删除成功", Map.of("doc_id", docId)));
    }

    @GetMapping("/{id}/faqs")
    public ResponseEntity<?> listFaqs(@PathVariable String id,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:read 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canViewKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权查看该知识库问答对");
        }
        return ResponseEntity.ok(ApiResponse.ok(knowledgeBaseService.searchFaqs(id, null, null, page, size)));
    }

    @PostMapping("/{id}/faqs")
    public ResponseEntity<?> createFaq(@PathVariable String id, @RequestBody CreateFaqRequest req) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canManageKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权创建问答对");
        }
        KnowledgeFaq faq = knowledgeBaseService.createFaq(id, req);
        return ResponseEntity.ok(ApiResponse.ok("创建问答成功", faq));
    }

    @PutMapping("/{id}/faqs/{faqId}")
    public ResponseEntity<?> updateFaq(@PathVariable String id, @PathVariable String faqId, @RequestBody UpdateFaqRequest req) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canManageKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权更新问答对");
        }
        KnowledgeFaq faq = knowledgeBaseService.updateFaq(id, faqId, req);
        return ResponseEntity.ok(ApiResponse.ok("更新问答成功", faq));
    }

    @DeleteMapping("/{id}/faqs/{faqId}")
    public ResponseEntity<?> deleteFaq(@PathVariable String id, @PathVariable String faqId) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_WRITE)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:write 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canManageKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权删除问答对");
        }
        knowledgeBaseService.deleteFaq(id, faqId);
        return ResponseEntity.ok(ApiResponse.ok("删除问答成功", Map.of("faq_id", faqId)));
    }

    @PostMapping("/{id}/retrieve")
    public ResponseEntity<?> retrieve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || !ctx.hasScope(OpenApiScopes.KB_READ)) {
            return error(HttpStatus.FORBIDDEN, "scope_missing", "当前凭证缺少 kb:read 权限");
        }
        KnowledgeBase kb = knowledgeBaseRepository.findById(id).orElse(null);
        if (kb == null || !authorizationService.canUseKnowledgeBase(ctx.asActor(), kb)) {
            return error(HttpStatus.FORBIDDEN, "kb_out_of_scope", "无权检索该知识库");
        }
        String query = body.get("query") != null ? body.get("query").toString() : "";
        if (query.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_parameter", "检索关键词 query 不能为空");
        }
        int topK = body.get("top_k") instanceof Number n ? n.intValue() : 3;
        List<RetrievedChunk> chunks = knowledgeBaseService.retrieveChunks(id, query, topK);
        usageRecorder.record("kb.retrieve", null, null, 200, null, 25, 0, 0, null);
        return ResponseEntity.ok(ApiResponse.ok(chunks));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> error(HttpStatus status, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx != null) {
            data.put("request_id", ctx.getRequestId());
        }
        return ResponseEntity.status(status).body(ApiResponse.error(message, data));
    }
}
