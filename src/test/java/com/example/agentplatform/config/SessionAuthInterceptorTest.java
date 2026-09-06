package com.example.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAuthInterceptorTest {

    private final SessionAuthInterceptor interceptor = new SessionAuthInterceptor();

    @Test
    void rejectsRequestWithoutAuthenticatedSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录已失效");
    }

    @Test
    void allowsRequestWithAuthenticatedSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        request.getSession().setAttribute(SessionAuthInterceptor.SESSION_USER, new Object());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }
}
