package com.shimeta.service.share;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.AgentShareVisitor;
import com.shimeta.model.ApiResponse;
import com.shimeta.model.AppUser;
import com.shimeta.model.ChatResponse;
import com.shimeta.model.OpenApiChatRequest;
import com.shimeta.model.OpenApiKey;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AgentShareLinkRepository;
import com.shimeta.repository.AgentShareVisitorRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.security.OpenApiContext;
import com.shimeta.security.ratelimit.RateLimiter;
import com.shimeta.service.AgentConversationService;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.OpenChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareChatServiceTest {

    private final ShareAccessService access = mock(ShareAccessService.class);
    private final OpenChatService openChat = mock(OpenChatService.class);
    private final OpenApiKeyService keyService = mock(OpenApiKeyService.class);
    private final AgentShareLinkRepository linkRepository = mock(AgentShareLinkRepository.class);
    private final AgentShareVisitorRepository visitorRepository = mock(AgentShareVisitorRepository.class);
    private final AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private ShareChatService service;
    private AgentShareLink link;

    @BeforeEach
    void setUp() {
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setName("内部客服");
        agent.setSystemPrompt("机密提示词");
        agent.setModelName("deepseek-flash");
        link = new AgentShareLink();
        link.setId("L1");
        link.setAgentId("a1");
        link.setCode("code-1");
        link.setAccessMode(AgentShareLink.MODE_ACCESS_CODE);
        link.setTitle("产品咨询");
        link.setWelcomeMessage("你好");
        link.setVisitorRpm(10);
        link.setApiKeyId("k1");
        AgentShareVisitor visitor = new AgentShareVisitor();
        visitor.setId("v1");
        visitor.setLinkId("L1");
        visitor.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        AppUser owner = new AppUser();
        owner.setId("u1");
        owner.setRole("DEVELOPER");
        when(access.requireActiveLink("code-1")).thenReturn(link);
        when(access.authenticate("code-1", "tok")).thenReturn(new ShareAccessService.VisitorSession(link, agent, visitor, owner));
        OpenApiKey key = new OpenApiKey();
        key.setId("k1");
        key.setScopes(List.of("chat"));
        when(keyService.findUsableManaged("k1")).thenReturn(Optional.of(key));
        when(rateLimiter.tryConsume(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(true, 10, 9, 60));
        when(linkRepository.reserveMessage(eq("L1"), any())).thenReturn(1);
        service = new ShareChatService(access, openChat, keyService, linkRepository, visitorRepository,
                conversationRepository, mock(AgentConversationService.class), rateLimiter);
    }

    @Test
    @DisplayName("访客能看到的只有标题、欢迎语等显示设置，没有提示词、模型和智能体名称")
    void exposesOnlyWhitelistedFields() {
        Map<String, Object> entry = service.entry("code-1");
        Map<String, Object> config = service.config("code-1", "tok");

        assertThat(entry).containsOnlyKeys("accessMode", "title");
        assertThat(config).containsOnlyKeys("title", "welcomeMessage", "exampleQuestions", "showRecordNotice",
                "visitorId", "tokenExpiresAt");
        assertThat(entry.toString() + config).doesNotContain("机密提示词", "deepseek-flash", "内部客服", "k1");
    }

    @Test
    @DisplayName("发消息：走开放接口链路，带内部凭证上下文和访客账号；别人的会话 ID 当作新会话；完成后累计 token")
    @SuppressWarnings("unchecked")
    void sendsThroughOpenChatWithVisitorAccount() {
        AgentConversation other = new AgentConversation();
        other.setId("conv-other");
        other.setAgentId("a1");
        other.setAccount("share:L1:someone-else");
        when(conversationRepository.findById("conv-other")).thenReturn(Optional.of(other));
        SseEmitter emitter = new SseEmitter();
        String[] seenAccount = new String[1];
        when(openChat.chat(any(), any(), any())).thenAnswer(inv -> {
            seenAccount[0] = OpenApiContext.get().getEndUser();
            assertThat(CurrentActor.get()).isNotNull();
            return emitter;
        });

        Object result = service.send("code-1", "tok", "  怎么发布节目？ ", "conv-other", new MockHttpServletRequest());

        assertThat(result).isSameAs(emitter);
        ArgumentCaptor<OpenApiChatRequest> req = ArgumentCaptor.forClass(OpenApiChatRequest.class);
        ArgumentCaptor<Consumer<ChatResponse>> done = ArgumentCaptor.forClass(Consumer.class);
        verify(openChat).chat(req.capture(), any(), done.capture());
        assertThat(req.getValue().getMessage()).isEqualTo("怎么发布节目？");
        assertThat(req.getValue().getConversationId()).isNull();
        assertThat(req.getValue().getEndUser()).isEqualTo("share:L1:v1");
        assertThat(seenAccount[0]).isEqualTo("share:L1:v1");
        assertThat(OpenApiContext.get()).isNull();
        assertThat(CurrentActor.get()).isNull();

        ChatResponse response = new ChatResponse();
        response.setTokensUsed(120);
        done.getValue().accept(response);
        verify(linkRepository).addTokens("L1", 120L);
        verify(visitorRepository).addTokens("v1", 120L);
    }

    @Test
    @DisplayName("对话次数用完：链接失效，不调用模型")
    void exhaustedLinkIsRejected() {
        when(linkRepository.reserveMessage(eq("L1"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.send("code-1", "tok", "你好", null, new MockHttpServletRequest()))
                .satisfies(e -> assertThat(((ShareAccessException) e).getCode()).isEqualTo(ShareAccessException.LINK_INVALID));
        verify(openChat, never()).chat(any(), any(), any());
    }

    @Test
    @DisplayName("访客发送太频繁：限流，不占用对话次数")
    void rateLimitedBeforeReserving() {
        when(rateLimiter.tryConsume(eq("share:v1"), anyInt(), eq(10))).thenReturn(new RateLimiter.Result(false, 10, 0, 30));
        assertThatThrownBy(() -> service.send("code-1", "tok", "你好", null, new MockHttpServletRequest()))
                .satisfies(e -> assertThat(((ShareAccessException) e).getCode()).isEqualTo(ShareAccessException.RATE_LIMITED));
        verify(linkRepository, never()).reserveMessage(any(), any());
    }

    @Test
    @DisplayName("开放接口的拒绝：智能体层面的原因改成链接失效，护栏拦截原样告诉访客")
    void mapsOpenChatRejections() {
        when(openChat.chat(any(), any(), any())).thenReturn(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("智能体未处于运行状态", Map.of("code", "agent_not_running"))));
        assertThatThrownBy(() -> service.send("code-1", "tok", "你好", null, new MockHttpServletRequest()))
                .satisfies(e -> {
                    assertThat(((ShareAccessException) e).getCode()).isEqualTo(ShareAccessException.LINK_INVALID);
                    assertThat(e.getMessage()).doesNotContain("智能体");
                });

        when(openChat.chat(any(), any(), any())).thenReturn(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("输入包含敏感内容", Map.of("code", "input_blocked"))));
        assertThatThrownBy(() -> service.send("code-1", "tok", "你好", null, new MockHttpServletRequest()))
                .satisfies(e -> {
                    assertThat(((ShareAccessException) e).getCode()).isEqualTo("input_blocked");
                    assertThat(((ShareAccessException) e).getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    @DisplayName("空消息和超长消息直接拒绝")
    void validatesMessage() {
        assertThatThrownBy(() -> service.send("code-1", "tok", "  ", null, new MockHttpServletRequest()))
                .isInstanceOf(ShareAccessException.class);
        assertThatThrownBy(() -> service.send("code-1", "tok", "a".repeat(4001), null, new MockHttpServletRequest()))
                .hasMessageContaining("4000");
    }
}
