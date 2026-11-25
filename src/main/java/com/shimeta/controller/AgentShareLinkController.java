package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.model.ConversationDetail;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.share.ShareLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 智能体分享链接的管理接口（控制台，需要登录） */
@RestController
public class AgentShareLinkController {

    private final ShareLinkService shareLinkService;

    public AgentShareLinkController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @GetMapping("/api/agents/{agentId}/share-links")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable String agentId) {
        return ResponseEntity.ok(ApiResponse.ok(shareLinkService.list(agentId, CurrentActor.get())));
    }

    @PostMapping("/api/agents/{agentId}/share-links")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@PathVariable String agentId,
                                                                   @RequestBody ShareLinkService.LinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("分享链接已创建", shareLinkService.create(agentId, request, CurrentActor.get())));
    }

    @PutMapping("/api/agents/{agentId}/share-links/{linkId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String agentId, @PathVariable String linkId,
                                                                   @RequestBody ShareLinkService.LinkRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("已保存", shareLinkService.update(agentId, linkId, request, CurrentActor.get())));
    }

    /** 查看访问码明文（每次查看写审计） */
    @GetMapping("/api/agents/{agentId}/share-links/{linkId}/access-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> accessCode(@PathVariable String agentId, @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok(shareLinkService.viewAccessCode(agentId, linkId, CurrentActor.get())));
    }

    @PostMapping("/api/agents/{agentId}/share-links/{linkId}/enable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enable(@PathVariable String agentId, @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok("链接已启用", shareLinkService.setEnabled(agentId, linkId, true, CurrentActor.get())));
    }

    @PostMapping("/api/agents/{agentId}/share-links/{linkId}/disable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(@PathVariable String agentId, @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok("链接已停用", shareLinkService.setEnabled(agentId, linkId, false, CurrentActor.get())));
    }

    @PostMapping("/api/agents/{agentId}/share-links/{linkId}/reset")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reset(@PathVariable String agentId, @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok("已生成新地址，旧地址立即失效",
                shareLinkService.reset(agentId, linkId, CurrentActor.get())));
    }

    @DeleteMapping("/api/agents/{agentId}/share-links/{linkId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String agentId, @PathVariable String linkId) {
        shareLinkService.delete(agentId, linkId, CurrentActor.get());
        return ResponseEntity.ok(ApiResponse.ok("分享链接已删除", null));
    }

    @GetMapping("/api/agents/{agentId}/share-links/{linkId}/visitors")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> visitors(@PathVariable String agentId, @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok(shareLinkService.visitors(agentId, linkId, CurrentActor.get())));
    }

    @GetMapping("/api/agents/{agentId}/share-links/{linkId}/conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> conversations(@PathVariable String agentId,
                                                                                @PathVariable String linkId) {
        return ResponseEntity.ok(ApiResponse.ok(shareLinkService.conversations(agentId, linkId, CurrentActor.get())));
    }

    @GetMapping("/api/agents/{agentId}/share-links/{linkId}/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationDetail>> conversation(@PathVariable String agentId, @PathVariable String linkId,
                                                                        @PathVariable String conversationId) {
        return shareLinkService.conversationDetail(agentId, linkId, conversationId, CurrentActor.get())
                .map(d -> ResponseEntity.ok(ApiResponse.ok(d)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("会话不存在")));
    }

    /** 当前用户 24 小时内到期的链接，用于「即将到期」提醒 */
    @GetMapping("/api/share-links/expiring")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> expiring() {
        return ResponseEntity.ok(ApiResponse.ok(shareLinkService.expiringSoon(CurrentActor.get())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException e) {
        HttpStatus status = e.getMessage() != null && (e.getMessage().startsWith("智能体不存在") || e.getMessage().contains("链接不存在"))
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
    }
}
