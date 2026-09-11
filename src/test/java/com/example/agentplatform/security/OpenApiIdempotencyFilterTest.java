package com.example.agentplatform.security;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.OpenApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiIdempotencyFilterTest {

    private OpenApiIdempotencyFilter filter;
    private AppUser owner;
    private OpenApiKey key;

    @BeforeEach
    void setUp() {
        filter = new OpenApiIdempotencyFilter();
        owner = new AppUser();
        owner.setId("usr-dev-idempotent");

        key = new OpenApiKey();
        key.setId("oak-idemp-1");
        key.setOwnerId(owner.getId());

        OpenApiContext ctx = new OpenApiContext("req-test-1", key, owner, "127.0.0.1");
        OpenApiContext.set(ctx);
    }

    @AfterEach
    void tearDown() {
        OpenApiContext.clear();
    }

    @Test
    @DisplayName("GET 请求不进行幂等拦截，直接放行")
    void getRequest_noIdempotency() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open/v1/agents");
        request.addHeader("Idempotency-Key", "test-key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
                res.getWriter().write("{\"agents\": []}");
            }
        };

        filter.doFilter(request, response, chain);
        assertThat(response.getContentAsString()).isEqualTo("{\"agents\": []}");
        assertThat(response.getHeader("X-Idempotent-Replay")).isNull();
        assertThat(filter.getCache().estimatedSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("POST 请求未带 Idempotency-Key 时正常执行，不写入缓存")
    void postRequest_withoutHeader_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/open/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
                res.getWriter().write("{\"created\": true}");
            }
        };

        filter.doFilter(request, response, chain);
        assertThat(response.getContentAsString()).isEqualTo("{\"created\": true}");
        assertThat(response.getHeader("X-Idempotent-Replay")).isNull();
        assertThat(filter.getCache().estimatedSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("POST 携带 Idempotency-Key：首次执行缓存，二次重放直接返回并标记 X-Idempotent-Replay")
    void postRequest_withHeader_replayedOnSecondCall() throws ServletException, IOException {
        String idemKey = "idemp_abc_123";
        int[] executionCount = new int[]{0};

        FilterChain downstream = (req, res) -> {
            executionCount[0]++;
            HttpServletResponse httpRes = (HttpServletResponse) res;
            httpRes.setStatus(200);
            httpRes.setContentType("application/json");
            httpRes.getOutputStream().write(("{\"agentId\": \"agt-" + executionCount[0] + "\"}").getBytes(StandardCharsets.UTF_8));
        };

        // 1. 第一次调用：执行下游业务
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/open/v1/agents");
        req1.addHeader("Idempotency-Key", idemKey);
        MockHttpServletResponse res1 = new MockHttpServletResponse();

        filter.doFilter(req1, res1, downstream);

        assertThat(executionCount[0]).isEqualTo(1);
        assertThat(res1.getStatus()).isEqualTo(200);
        assertThat(res1.getContentAsString()).isEqualTo("{\"agentId\": \"agt-1\"}");
        assertThat(res1.getHeader("X-Idempotent-Replay")).isNull();
        assertThat(filter.getCache().estimatedSize()).isEqualTo(1);

        // 2. 第二次重复调用：应命中缓存，不执行下游业务 (executionCount 仍为 1)
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/open/v1/agents");
        req2.addHeader("Idempotency-Key", idemKey);
        MockHttpServletResponse res2 = new MockHttpServletResponse();

        filter.doFilter(req2, res2, downstream);

        assertThat(executionCount[0]).isEqualTo(1); // 下游没被二次触发
        assertThat(res2.getStatus()).isEqualTo(200);
        assertThat(res2.getContentAsString()).isEqualTo("{\"agentId\": \"agt-1\"}");
        assertThat(res2.getHeader("X-Idempotent-Replay")).isEqualTo("true");

        // 3. 不同的 Idempotency-Key：应执行下游业务 (executionCount 变为 2)
        MockHttpServletRequest req3 = new MockHttpServletRequest("POST", "/open/v1/agents");
        req3.addHeader("Idempotency-Key", "idemp_different_456");
        MockHttpServletResponse res3 = new MockHttpServletResponse();

        filter.doFilter(req3, res3, downstream);

        assertThat(executionCount[0]).isEqualTo(2);
        assertThat(res3.getContentAsString()).isEqualTo("{\"agentId\": \"agt-2\"}");
        assertThat(res3.getHeader("X-Idempotent-Replay")).isNull();
        assertThat(filter.getCache().estimatedSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("下游返回 500 异常时不缓存，允许客户端重试")
    void postRequest_500Error_notCached() throws ServletException, IOException {
        String idemKey = "idemp_fail_1";
        FilterChain errorDownstream = (req, res) -> {
            HttpServletResponse httpRes = (HttpServletResponse) res;
            httpRes.setStatus(500);
            httpRes.getWriter().write("{\"error\": \"db timeout\"}");
        };

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/open/v1/agents");
        req.addHeader("Idempotency-Key", idemKey);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, errorDownstream);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(filter.getCache().estimatedSize()).isEqualTo(0);
    }
}
