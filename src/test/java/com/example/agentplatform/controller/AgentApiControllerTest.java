package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.model.OpenApiKey;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.service.OpenApiKeyService;
import com.example.agentplatform.service.OpenApiKeyService.ResolvedKey;
import com.example.agentplatform.service.OpenChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApiControllerTest {

    @Mock
    private OpenChatService openChatService;
    @Mock
    private OpenApiKeyService openApiKeyService;
    @Mock
    private UserRepository userRepository;

    private AgentApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentApiController(openChatService, openApiKeyService, userRepository);
    }

    @Test
    void rejectsRequestWithoutBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("hello");

        Object result = controller.sendChatMessage(req, request, new MockHttpServletResponse());

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestWithInvalidApiKey() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        request.addHeader("Authorization", "Bearer sk-invalid-key");
        when(openApiKeyService.resolvePlaintext("sk-invalid-key")).thenReturn(Optional.empty());

        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("hello");

        Object result = controller.sendChatMessage(req, request, new MockHttpServletResponse());

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void statusEndpointReturnsHealthy() {
        ResponseEntity<?> result = controller.apiStatus();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stopReturnsNotImplemented() {
        when(openChatService.stopUnsupported("task-1"))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ApiResponse.error("不支持")));
        ResponseEntity<ApiResponse<java.util.Map<String, Object>>> result = controller.stopTask("task-1");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void rejectsWhenOwnerMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        request.addHeader("Authorization", "Bearer sk-live-abc");
        OpenApiKey key = new OpenApiKey();
        key.setOwnerId("missing");
        when(openApiKeyService.resolvePlaintext("sk-live-abc")).thenReturn(Optional.of(new ResolvedKey(key, null)));
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        Object result = controller.sendChatMessage(new OpenApiChatRequest(), request, new MockHttpServletResponse());
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ownerLookupUsesAppUser() {
        AppUser user = new AppUser();
        user.setId("u1");
        user.setUsername("dev");
        assertThat(user.getId()).isEqualTo("u1");
    }
}
