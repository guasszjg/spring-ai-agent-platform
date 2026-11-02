package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.model.OpenApiChatRequest;
import com.shimeta.service.OpenChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 兼容地址 /api/v1：鉴权交给开放接口过滤器，控制器只转发给对话服务 */
@ExtendWith(MockitoExtension.class)
class AgentApiControllerTest {

    @Mock
    private OpenChatService openChatService;

    private AgentApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentApiController(openChatService);
    }

    @Test
    void chatDelegatesToOpenChatServiceAndMarksDeprecated() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat-messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OpenApiChatRequest req = new OpenApiChatRequest();
        req.setMessage("hello");
        Object expected = ResponseEntity.ok("ok");
        when(openChatService.chat(req, request)).thenReturn(expected);

        Object result = controller.sendChatMessage(req, request, response);

        assertThat(result).isSameAs(expected);
        verify(openChatService).chat(req, request);
        assertThat(response.getHeader("Deprecation")).isEqualTo("true");
        assertThat(response.getHeader("Link")).contains("/open/v1/chat-messages");
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
}
