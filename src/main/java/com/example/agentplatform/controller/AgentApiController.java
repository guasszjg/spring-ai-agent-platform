package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiContext;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.OpenApiKeyService.ResolvedKey;
import com.example.agentplatform.service.OpenChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AgentApiController {

    private final OpenChatService openChatService;
    private final OpenApiKeyService openApiKeyService;
    private final UserRepository userRepository;

    public AgentApiController(OpenChatService openChatService,
                              OpenApiKeyService openApiKeyService,
                              UserRepository userRepository) {
        this.openChatService = openChatService;
        this.openApiKeyService = openApiKeyService;
        this.userRepository = userRepository;
    }

    @PostMapping(value = "/chat-messages", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object sendChatMessage(@RequestBody OpenApiChatRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        httpResponse.setHeader("Deprecation", "true");
        httpResponse.setHeader("Link", "</open/v1/chat-messages>; rel=\"successor-version\"");
        String apiKey = extractApiKey(httpRequest);
        if (apiKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("API Key 未提供或格式不正确，请在请求头设置 Authorization: Bearer <API-KEY>"));
        }
        Optional<ResolvedKey> resolved = openApiKeyService.resolvePlaintext(apiKey);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("无效的 API Key，找不到对应的智能体"));
        }
        AppUser owner = userRepository.findById(resolved.get().key().getOwnerId()).orElse(null);
        if (owner == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("凭证所属开发者不可用"));
        }
        String requestId = "req_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        httpResponse.setHeader("X-Request-Id", requestId);
        OpenApiContext ctx = new OpenApiContext(requestId, resolved.get().key(), owner, httpRequest.getRemoteAddr());
        OpenApiContext.set(ctx);
        CurrentActor.set(ctx.asActor());
        try {
            return openChatService.chat(request, httpRequest);
        } finally {
            OpenApiContext.clear();
            CurrentActor.clear();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apiStatus() {
        return ResponseEntity.ok(ApiResponse.ok("API 服务运行正常", Map.of(
                "version", "v1.0",
                "status", "RUNNING",
                "timestamp", System.currentTimeMillis()
        )));
    }

    @PostMapping("/chat-messages/{taskId}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopTask(@PathVariable String taskId) {
        return openChatService.stopUnsupported(taskId);
    }

    private String extractApiKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String customKey = request.getHeader("X-API-Key");
        return customKey != null && !customKey.isBlank() ? customKey.trim() : null;
    }
}
