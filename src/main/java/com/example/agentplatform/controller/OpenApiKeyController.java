package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.CreateOpenApiKeyRequest;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.service.OpenApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/open-api-keys")
public class OpenApiKeyController {

    private final OpenApiKeyService openApiKeyService;

    public OpenApiKeyController(OpenApiKeyService openApiKeyService) {
        this.openApiKeyService = openApiKeyService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> list = openApiKeyService.listForActor(CurrentActor.get()).stream()
                .map(openApiKeyService::toView)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CreateOpenApiKeyRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("凭证已创建，明文仅显示一次", openApiKeyService.create(request, CurrentActor.get())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            OpenApiKey key = openApiKeyService.updateStatus(id, body.get("status"), CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok(openApiKeyService.toView(key)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        try {
            openApiKeyService.delete(id, CurrentActor.get());
            return ResponseEntity.ok(ApiResponse.ok("凭证已删除，对应密钥立即失效", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
