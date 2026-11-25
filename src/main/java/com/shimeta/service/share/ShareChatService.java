package com.shimeta.service.share;

import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentConversationMessage;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.ApiResponse;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 访客对话：每条消息都重新校验链接，再走开放接口的同一条链路（护栏、计费、审计、智能体统计）。
 * 返回给访客的字段按白名单，不含提示词、知识库、模型和通道
 */
@Service
public class ShareChatService {

    static final int MAX_MESSAGE_LENGTH = 4000;
    /** 开放接口里这些拒绝原因说明的是智能体或凭证本身，对访客统一按链接失效处理 */
    private static final Set<String> LINK_LEVEL_ERRORS = Set.of(
            "key_invalid", "scope_missing", "agent_out_of_scope", "agent_not_running", "dependency_invalid", "kill_switch");

    private final ShareAccessService accessService;
    private final OpenChatService openChatService;
    private final OpenApiKeyService keyService;
    private final AgentShareLinkRepository linkRepository;
    private final AgentShareVisitorRepository visitorRepository;
    private final AgentConversationRepository conversationRepository;
    private final AgentConversationService conversationService;
    private final RateLimiter rateLimiter;

    public ShareChatService(ShareAccessService accessService,
                            OpenChatService openChatService,
                            OpenApiKeyService keyService,
                            AgentShareLinkRepository linkRepository,
                            AgentShareVisitorRepository visitorRepository,
                            AgentConversationRepository conversationRepository,
                            AgentConversationService conversationService,
                            RateLimiter rateLimiter) {
        this.accessService = accessService;
        this.openChatService = openChatService;
        this.keyService = keyService;
        this.linkRepository = linkRepository;
        this.visitorRepository = visitorRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
    }

    /** 打开链接、还没进入时能看到的：访问方式和标题 */
    public Map<String, Object> entry(String code) {
        AgentShareLink link = accessService.requireActiveLink(code);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("accessMode", link.getAccessMode());
        v.put("title", displayTitle(link));
        return v;
    }

    /** 进入后的聊天页配置 */
    public Map<String, Object> config(String code, String token) {
        ShareAccessService.VisitorSession s = accessService.authenticate(code, token);
        AgentShareLink link = s.link();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("title", displayTitle(link));
        v.put("welcomeMessage", link.getWelcomeMessage());
        v.put("exampleQuestions", link.getExampleQuestions() == null ? List.of() : link.getExampleQuestions());
        v.put("showRecordNotice", !Boolean.FALSE.equals(link.getShowRecordNotice()));
        v.put("visitorId", s.visitor().getId());
        v.put("tokenExpiresAt", s.visitor().getTokenExpiresAt());
        return v;
    }

    /** 访客自己的会话列表 */
    public List<Map<String, Object>> conversations(String code, String token) {
        ShareAccessService.VisitorSession s = accessService.authenticate(code, token);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentConversation c : conversationRepository.findTop50ByAgentIdAndAccountOrderByUpdatedAtDesc(
                s.agent().getId(), account(s))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("title", c.getTitle());
            item.put("updatedAt", c.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    /** 访客自己某个会话的消息（只含角色、内容和时间） */
    public List<Map<String, Object>> messages(String code, String token, String conversationId) {
        ShareAccessService.VisitorSession s = accessService.authenticate(code, token);
        var detail = conversationService.getDetail(s.agent().getId(), conversationId)
                .filter(d -> account(s).equals(d.getConversation().getAccount()))
                .orElseThrow(() -> new ShareAccessException(ShareAccessException.BAD_REQUEST, HttpStatus.NOT_FOUND, "会话不存在"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentConversationMessage m : detail.getMessages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 发一条消息，返回开放接口的流式响应（SseEmitter），或拒绝时的 JSON。
     * 停止生成：访客断开连接即可，开放接口检测到断开后会让模型停止生成
     */
    public Object send(String code, String token, String message, String conversationId, HttpServletRequest httpRequest) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            throw new ShareAccessException(ShareAccessException.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请输入内容");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw new ShareAccessException(ShareAccessException.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "单条消息最多 " + MAX_MESSAGE_LENGTH + " 个字");
        }
        ShareAccessService.VisitorSession s = accessService.authenticate(code, token);
        AgentShareLink link = s.link();
        int rpm = link.getVisitorRpm() != null ? link.getVisitorRpm() : ShareLinkService.DEFAULT_VISITOR_RPM;
        RateLimiter.Result limit = rateLimiter.tryConsume("share:" + s.visitor().getId(), 1, rpm);
        if (!limit.allowed()) {
            throw new ShareAccessException(ShareAccessException.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                    "发送太频繁，请稍后再试", Math.max(1, limit.resetSeconds()));
        }
        // 预占一次对话：用完了就按链接失效处理
        LocalDateTime now = LocalDateTime.now();
        if (linkRepository.reserveMessage(link.getId(), now) == 0) {
            throw ShareAccessException.linkInvalid();
        }
        String ip = clientIp(httpRequest);
        visitorRepository.recordMessage(s.visitor().getId(), now, limit(ip, 64));

        String account = account(s);
        // 只能接着自己的会话聊，别人的会话 ID 当作新会话
        String convId = conversationId == null || conversationId.isBlank() ? null
                : conversationRepository.findById(conversationId.trim())
                .filter(c -> s.agent().getId().equals(c.getAgentId()) && account.equals(c.getAccount()))
                .map(AgentConversation::getId).orElse(null);

        OpenApiChatRequest request = new OpenApiChatRequest();
        request.setAgentId(s.agent().getId());
        request.setMessage(text);
        request.setConversationId(convId);
        request.setEndUser(account);
        request.setResponseMode("streaming");

        OpenApiKey key = keyService.findUsableManaged(link.getApiKeyId()).orElseThrow(ShareAccessException::linkInvalid);
        OpenApiContext ctx = new OpenApiContext("share-" + UUID.randomUUID().toString().substring(0, 12), key, s.owner(), ip);
        ctx.setEndUser(account);
        String linkId = link.getId();
        String visitorId = s.visitor().getId();
        OpenApiContext.set(ctx);
        CurrentActor.set(ctx.asActor());
        try {
            Object result = openChatService.chat(request, httpRequest, response -> recordTokens(linkId, visitorId, response));
            return sanitizeRejection(result);
        } finally {
            OpenApiContext.clear();
            CurrentActor.clear();
        }
    }

    private void recordTokens(String linkId, String visitorId, ChatResponse response) {
        long tokens = response.getTokensUsed() != null ? Math.max(0, response.getTokensUsed()) : 0;
        if (tokens > 0) {
            linkRepository.addTokens(linkId, tokens);
            visitorRepository.addTokens(visitorId, tokens);
        }
    }

    /** 开放接口在开始生成前的拒绝：智能体和凭证层面的原因改成「链接已失效」，护栏等原因原样告诉访客 */
    private Object sanitizeRejection(Object result) {
        if (!(result instanceof ResponseEntity<?> entity) || entity.getStatusCode().is2xxSuccessful()) {
            return result;
        }
        String code = null;
        String message = "当前无法回答，请稍后再试";
        if (entity.getBody() instanceof ApiResponse<?> body) {
            if (body.getData() instanceof Map<?, ?> data && data.get("code") != null) {
                code = String.valueOf(data.get("code"));
            }
            if (body.getMessage() != null && !body.getMessage().isBlank()) {
                message = body.getMessage();
            }
        }
        if (code == null || LINK_LEVEL_ERRORS.contains(code)) {
            throw ShareAccessException.linkInvalid();
        }
        if ("upstream_error".equals(code)) {
            // 模型失败的具体原因含通道等内部信息，只给平台用户看，访客只看到通用提示
            throw new ShareAccessException(code, HttpStatus.BAD_GATEWAY, "当前无法回答，请稍后再试");
        }
        throw new ShareAccessException(code, HttpStatus.valueOf(entity.getStatusCode().value()), message);
    }

    private static String account(ShareAccessService.VisitorSession s) {
        return ShareLinkService.accountOf(s.link().getId(), s.visitor().getId());
    }

    private static String displayTitle(AgentShareLink link) {
        return link.getTitle() != null && !link.getTitle().isBlank() ? link.getTitle() : "智能助手";
    }

    static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
