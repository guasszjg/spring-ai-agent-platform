package com.example.agentplatform.controller;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.ChatRequest;
import com.example.agentplatform.model.ChatResponse;
import com.example.agentplatform.model.OpenApiChatRequest;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.service.AiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApiControllerTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AiChatService aiChatService;

    private AgentApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentApiController(agentRepository, aiChatService);
    }

    @Test
    void rejectsRequestWithoutBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("hello");

        Object result = controller.sendChatMessage(req, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestWithInvalidApiKey() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        request.addHeader("Authorization", "Bearer sk-invalid-key");
        when(agentRepository.findByApiKey("sk-invalid-key")).thenReturn(Optional.empty());

        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("hello");

        Object result = controller.sendChatMessage(req, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void returnsBadRequestWhenMessageIsEmpty() {
        String validKey = "sk-agent-valid-123456";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        request.addHeader("Authorization", "Bearer " + validKey);

        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setName("Test Agent");
        agent.setApiKey(validKey);
        when(agentRepository.findByApiKey(validKey)).thenReturn(Optional.of(agent));

        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("   ");

        Object result = controller.sendChatMessage(req, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void successfullyProcessesBlockingChat() {
        String validKey = "sk-agent-valid-123456";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        request.addHeader("Authorization", "Bearer " + validKey);

        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setName("Test Agent");
        agent.setApiKey(validKey);
        agent.setModelName("deepseek-chat");
        when(agentRepository.findByApiKey(validKey)).thenReturn(Optional.of(agent));

        ChatResponse mockResp = new ChatResponse();
        mockResp.setReply("Hi there!");
        mockResp.setConversationId("conv-123");
        mockResp.setModel("deepseek-chat");
        mockResp.setTokensUsed(25);
        mockResp.setLatencyMs(150L);
        when(aiChatService.chat(any(ChatRequest.class))).thenReturn(mockResp);

        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("Hello!");
        req.setResponseMode("blocking");

        Object result = controller.sendChatMessage(req, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statusEndpointReturnsHealthy() {
        ResponseEntity<?> result = controller.apiStatus();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
