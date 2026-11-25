package com.shimeta.service.share;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentConversation;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.AgentShareVisitor;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AppUser;
import com.shimeta.model.ConversationDetail;
import com.shimeta.model.OpenApiKey;
import com.shimeta.repository.AgentConversationRepository;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.AgentShareLinkRepository;
import com.shimeta.repository.AgentShareVisitorRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AgentConversationService;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformSettingService;
import com.shimeta.service.ResourceAuthorizationService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 分享链接的管理（链接创建人一侧）：新建、修改、停用 / 启用、重置、删除、用量与访客对话。
 * 权限与智能体一致：能管理这个智能体的人才能管理它的链接。所有修改都写审计
 */
@Service
public class ShareLinkService {

    static final int MAX_LINKS_PER_AGENT = 50;
    static final int DEFAULT_EXPIRE_DAYS = 7;
    static final int MAX_EXPIRE_DAYS = 365;
    static final int DEFAULT_MAX_MESSAGES = 200;
    static final int DEFAULT_VISITOR_RPM = 10;
    static final int MAX_VISITOR_RPM = 120;
    static final int MAX_EXAMPLES = 6;
    /** 到期前多久提醒创建人 */
    static final int EXPIRING_SOON_HOURS = 24;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final AgentShareLinkRepository linkRepository;
    private final AgentShareVisitorRepository visitorRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final AgentConversationRepository conversationRepository;
    private final AgentConversationService conversationService;
    private final ResourceAuthorizationService authorizationService;
    private final OpenApiKeyService keyService;
    private final PlatformSettingService settingService;
    private final AuditRecorder auditRecorder;

    public ShareLinkService(AgentShareLinkRepository linkRepository,
                            AgentShareVisitorRepository visitorRepository,
                            AgentRepository agentRepository,
                            UserRepository userRepository,
                            AgentConversationRepository conversationRepository,
                            AgentConversationService conversationService,
                            ResourceAuthorizationService authorizationService,
                            OpenApiKeyService keyService,
                            PlatformSettingService settingService,
                            AuditRecorder auditRecorder) {
        this.linkRepository = linkRepository;
        this.visitorRepository = visitorRepository;
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.authorizationService = authorizationService;
        this.keyService = keyService;
        this.settingService = settingService;
        this.auditRecorder = auditRecorder;
    }

    /** 新建或修改链接的请求。修改时字段为空表示不改；各项上限填 0 表示不限 */
    public record LinkRequest(
            String name,
            String accessMode,
            String accessCode,
            Integer expiresInDays,
            LocalDateTime expiresAt,
            Integer maxMessages,
            Long maxTokens,
            Integer maxVisitors,
            Integer visitorRpm,
            String title,
            String welcomeMessage,
            List<String> exampleQuestions,
            Boolean showRecordNotice
    ) {
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String agentId, CurrentActor actor) {
        Agent agent = requireManageable(agentId, actor);
        boolean publicEnabled = settingService.sharePublicEnabled();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentShareLink link : linkRepository.findByAgentIdOrderByCreatedAtDesc(agentId)) {
            result.add(toView(link, agent, publicEnabled, null));
        }
        return result;
    }

    /** 当前用户能管理的、24 小时内到期且仍启用的链接，用于列表上的「即将到期」提醒 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> expiringSoon(CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime soon = now.plusHours(EXPIRING_SOON_HOURS);
        Map<String, Agent> agents = new LinkedHashMap<>();
        for (Agent agent : agentRepository.findAll()) {
            if (authorizationService.canManageAgent(actor, agent)) {
                agents.put(agent.getId(), agent);
            }
        }
        if (agents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentShareLink link : linkRepository.findByAgentIdIn(agents.keySet())) {
            if (AgentShareLink.STATUS_ACTIVE.equals(link.getStatus()) && link.getExpiresAt() != null
                    && link.getExpiresAt().isAfter(now) && !link.getExpiresAt().isAfter(soon)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", link.getId());
                item.put("agentId", link.getAgentId());
                item.put("agentName", agents.get(link.getAgentId()).getName());
                item.put("name", link.getName());
                item.put("expiresAt", link.getExpiresAt());
                result.add(item);
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> visitors(String agentId, String linkId, CurrentActor actor) {
        requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentShareVisitor v : visitorRepository.findByLinkIdOrderByLastSeenAtDesc(link.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", v.getId());
            item.put("label", visitorLabel(v.getId()));
            item.put("messageCount", v.getMessageCount());
            item.put("tokenCount", v.getTokenCount());
            item.put("firstSeenAt", v.getFirstSeenAt());
            item.put("lastSeenAt", v.getLastSeenAt());
            result.add(item);
        }
        return result;
    }

    /** 链接下所有访客的会话（最近 200 个），用于创建人复盘 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversations(String agentId, String linkId, CurrentActor actor) {
        requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentConversation c : conversationRepository.findTop200ByAgentIdAndAccountStartingWithOrderByUpdatedAtDesc(
                agentId, accountPrefix(link.getId()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("title", c.getTitle());
            item.put("visitor", visitorLabel(visitorIdOf(c.getAccount())));
            item.put("messageCount", c.getMessageCount());
            item.put("totalTokens", c.getTotalTokens());
            item.put("createdAt", c.getCreatedAt());
            item.put("updatedAt", c.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<ConversationDetail> conversationDetail(String agentId, String linkId,
                                                           String conversationId, CurrentActor actor) {
        requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        return conversationService.getDetail(agentId, conversationId)
                .filter(d -> d.getConversation().getAccount() != null
                        && d.getConversation().getAccount().startsWith(accountPrefix(link.getId())));
    }

    // ==================== 修改 ====================

    /** 新建链接。返回的视图里带一次访问码明文（只在新建和修改访问码时返回） */
    @Transactional
    public Map<String, Object> create(String agentId, LinkRequest request, CurrentActor actor) {
        Agent agent = requireManageable(agentId, actor);
        if (linkRepository.findByAgentIdOrderByCreatedAtDesc(agentId).size() >= MAX_LINKS_PER_AGENT) {
            throw new IllegalArgumentException("每个智能体最多 " + MAX_LINKS_PER_AGENT + " 个分享链接，请先删除不用的链接");
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("请填写链接名称");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentShareLink link = new AgentShareLink();
        link.setId(UUID.randomUUID().toString().replace("-", ""));
        link.setAgentId(agentId);
        link.setCode(newUniqueCode());
        link.setName(limit(request.name().trim(), 100));
        String mode = normalizeMode(request.accessMode());
        requirePublicAllowed(mode);
        link.setAccessMode(mode);
        String plainCode = null;
        if (AgentShareLink.MODE_ACCESS_CODE.equals(mode)) {
            plainCode = validAccessCode(request.accessCode());
            link.setAccessCodeHash(ENCODER.encode(plainCode));
        }
        link.setExpiresAt(resolveExpiry(request, now, DEFAULT_EXPIRE_DAYS));
        // 不填时默认 200 次；填 0 表示不限（注意不能写成三元表达式，int 和 null 混用会自动拆箱抛空指针）
        if (request.maxMessages() == null) {
            link.setMaxMessages(DEFAULT_MAX_MESSAGES);
        } else {
            link.setMaxMessages(positiveOrNull(request.maxMessages()));
        }
        link.setMaxTokens(request.maxTokens() == null ? null : positiveOrNull(request.maxTokens()));
        link.setMaxVisitors(request.maxVisitors() == null ? null : positiveOrNull(request.maxVisitors()));
        link.setVisitorRpm(validRpm(request.visitorRpm() == null ? DEFAULT_VISITOR_RPM : request.visitorRpm()));
        applyDisplay(link, request);
        if (link.getTitle() == null) {
            // 默认标题用智能体名称；创建人在编辑页能看到并修改，访客在进入前也会看到它
            link.setTitle(limit(agent.getName() == null ? "智能助手" : agent.getName(), 100));
        }
        if (link.getShowRecordNotice() == null) {
            link.setShowRecordNotice(true);
        }
        link.setStatus(AgentShareLink.STATUS_ACTIVE);
        link.setCreatedBy(actor.getUserId());
        link.setApiKeyId(issueKey(agent, link, actor).getId());
        link = linkRepository.save(link);
        auditRecorder.record("share_link.create", "SHARE_LINK", link.getId(), "SUCCESS", null, "MEDIUM",
                "agent=" + agentId + " mode=" + mode + " expiresAt=" + link.getExpiresAt());
        return toView(link, agent, settingService.sharePublicEnabled(), plainCode);
    }

    @Transactional
    public Map<String, Object> update(String agentId, String linkId, LinkRequest request, CurrentActor actor) {
        Agent agent = requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        if (request == null) {
            throw new IllegalArgumentException("没有要修改的内容");
        }
        List<String> changes = new ArrayList<>();
        String plainCode = null;
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new IllegalArgumentException("链接名称不能为空");
            }
            link.setName(limit(request.name().trim(), 100));
        }
        String mode = link.getAccessMode();
        if (request.accessMode() != null) {
            mode = normalizeMode(request.accessMode());
            if (!mode.equals(link.getAccessMode())) {
                requirePublicAllowed(mode);
                changes.add("mode=" + mode);
            }
        }
        if (AgentShareLink.MODE_ACCESS_CODE.equals(mode)) {
            boolean needCode = link.getAccessCodeHash() == null;
            if (request.accessCode() != null || needCode) {
                plainCode = validAccessCode(request.accessCode());
                link.setAccessCodeHash(ENCODER.encode(plainCode));
                changes.add("accessCode");
            }
        } else {
            link.setAccessCodeHash(null);
        }
        link.setAccessMode(mode);
        if (request.expiresInDays() != null || request.expiresAt() != null) {
            link.setExpiresAt(resolveExpiry(request, LocalDateTime.now(), DEFAULT_EXPIRE_DAYS));
            changes.add("expiresAt=" + link.getExpiresAt());
        }
        if (request.maxMessages() != null) {
            link.setMaxMessages(positiveOrNull(request.maxMessages()));
            changes.add("maxMessages=" + link.getMaxMessages());
        }
        if (request.maxTokens() != null) {
            link.setMaxTokens(positiveOrNull(request.maxTokens()));
            changes.add("maxTokens=" + link.getMaxTokens());
        }
        if (request.maxVisitors() != null) {
            link.setMaxVisitors(positiveOrNull(request.maxVisitors()));
            changes.add("maxVisitors=" + link.getMaxVisitors());
        }
        if (request.visitorRpm() != null) {
            link.setVisitorRpm(validRpm(request.visitorRpm()));
        }
        applyDisplay(link, request);
        link = linkRepository.save(link);
        auditRecorder.record("share_link.update", "SHARE_LINK", link.getId(), "SUCCESS", null, "LOW",
                changes.isEmpty() ? "display" : String.join(" ", changes));
        return toView(link, agent, settingService.sharePublicEnabled(), plainCode);
    }

    @Transactional
    public Map<String, Object> setEnabled(String agentId, String linkId, boolean enabled, CurrentActor actor) {
        Agent agent = requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        if (enabled) {
            requirePublicAllowed(link.getAccessMode());
        }
        link.setStatus(enabled ? AgentShareLink.STATUS_ACTIVE : AgentShareLink.STATUS_DISABLED);
        link = linkRepository.save(link);
        auditRecorder.record(enabled ? "share_link.enable" : "share_link.disable", "SHARE_LINK", link.getId(),
                "SUCCESS", null, "MEDIUM", null);
        return toView(link, agent, settingService.sharePublicEnabled(), null);
    }

    /** 重置：换新的链接码和内部凭证，旧地址和所有访客令牌立即失效 */
    @Transactional
    public Map<String, Object> reset(String agentId, String linkId, CurrentActor actor) {
        Agent agent = requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        keyService.revokeManaged(link.getApiKeyId());
        link.setCode(newUniqueCode());
        link.setApiKeyId(issueKey(agent, link, actor).getId());
        visitorRepository.revokeTokens(link.getId());
        link = linkRepository.save(link);
        auditRecorder.record("share_link.reset", "SHARE_LINK", link.getId(), "SUCCESS", null, "HIGH", null);
        return toView(link, agent, settingService.sharePublicEnabled(), null);
    }

    /** 删除链接、它的内部凭证和访客记录；访客的对话记录保留在智能体日志里 */
    @Transactional
    public void delete(String agentId, String linkId, CurrentActor actor) {
        requireManageable(agentId, actor);
        AgentShareLink link = requireLink(agentId, linkId);
        keyService.revokeManaged(link.getApiKeyId());
        visitorRepository.deleteByLinkId(link.getId());
        linkRepository.delete(link);
        auditRecorder.record("share_link.delete", "SHARE_LINK", link.getId(), "SUCCESS", null, "HIGH", link.getName());
    }

    // ==================== 内部 ====================

    /** 链接当前对访客的状态：ACTIVE 可用，其余为不可用的原因（只给创建人看） */
    public static String effectiveState(AgentShareLink link, Agent agent, boolean publicEnabled, LocalDateTime now) {
        if (!AgentShareLink.STATUS_ACTIVE.equals(link.getStatus())) {
            return "DISABLED";
        }
        if (link.isExpired(now)) {
            return "EXPIRED";
        }
        if (link.messagesExhausted() || link.tokensExhausted()) {
            return "EXHAUSTED";
        }
        if (AgentShareLink.MODE_PUBLIC.equals(link.getAccessMode()) && !publicEnabled) {
            return "PUBLIC_DISABLED";
        }
        if (agent == null || agent.getStatus() != AgentStatus.RUNNING) {
            return "AGENT_NOT_RUNNING";
        }
        return "ACTIVE";
    }

    Map<String, Object> toView(AgentShareLink link, Agent agent, boolean publicEnabled, String plainAccessCode) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", link.getId());
        v.put("agentId", link.getAgentId());
        v.put("code", link.getCode());
        v.put("path", "/chat/" + link.getCode());
        v.put("name", link.getName());
        v.put("accessMode", link.getAccessMode());
        v.put("hasAccessCode", link.getAccessCodeHash() != null);
        if (plainAccessCode != null) {
            v.put("accessCode", plainAccessCode);
        }
        v.put("expiresAt", link.getExpiresAt());
        v.put("expiringSoon", AgentShareLink.STATUS_ACTIVE.equals(link.getStatus()) && link.getExpiresAt() != null
                && link.getExpiresAt().isAfter(now) && !link.getExpiresAt().isAfter(now.plusHours(EXPIRING_SOON_HOURS)));
        v.put("maxMessages", link.getMaxMessages());
        v.put("maxTokens", link.getMaxTokens());
        v.put("maxVisitors", link.getMaxVisitors());
        v.put("visitorRpm", link.getVisitorRpm());
        v.put("title", link.getTitle());
        v.put("welcomeMessage", link.getWelcomeMessage());
        v.put("exampleQuestions", link.getExampleQuestions() == null ? List.of() : link.getExampleQuestions());
        v.put("showRecordNotice", !Boolean.FALSE.equals(link.getShowRecordNotice()));
        v.put("status", link.getStatus());
        v.put("state", effectiveState(link, agent, publicEnabled, now));
        v.put("messageCount", link.getMessageCount());
        v.put("tokenCount", link.getTokenCount());
        v.put("visitorCount", link.getVisitorCount());
        v.put("lastUsedAt", link.getLastUsedAt());
        v.put("createdBy", link.getCreatedBy());
        v.put("createdAt", link.getCreatedAt());
        v.put("updatedAt", link.getUpdatedAt());
        return v;
    }

    /** 访客对话记录的 account：share:{linkId}:{visitorId} */
    public static String accountOf(String linkId, String visitorId) {
        return accountPrefix(linkId) + visitorId;
    }

    static String accountPrefix(String linkId) {
        return "share:" + linkId + ":";
    }

    static String visitorIdOf(String account) {
        if (account == null) {
            return "";
        }
        int i = account.lastIndexOf(':');
        return i >= 0 ? account.substring(i + 1) : account;
    }

    static String visitorLabel(String visitorId) {
        return "访客 " + (visitorId == null || visitorId.length() < 6 ? visitorId : visitorId.substring(0, 6));
    }

    /**
     * 内部凭证的所有者：智能体的所有者（存在的真实账号），否则是操作人。
     * 系统公共智能体只有超级管理员能管理，所以此时所有者是超级管理员
     */
    private OpenApiKey issueKey(Agent agent, AgentShareLink link, CurrentActor actor) {
        String ownerId = agent.getOwnerId();
        Optional<AppUser> owner = ownerId == null || ownerId.isBlank() ? Optional.empty() : userRepository.findById(ownerId);
        String effectiveOwner = owner.map(AppUser::getId).orElse(actor.getUserId());
        return keyService.createManagedChatKey(effectiveOwner, actor.getUserId(), actor.getAuthVersion(), agent.getId(),
                "分享链接「" + link.getName() + "」", OpenApiKeyService.MANAGED_BY_SHARE_LINK);
    }

    private Agent requireManageable(String agentId, CurrentActor actor) {
        if (actor == null) {
            throw new IllegalStateException("登录已失效，请重新登录");
        }
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + agentId));
        if (actor.isViewer() || !authorizationService.canManageAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：只有能管理该智能体的人可以管理它的分享链接");
        }
        return agent;
    }

    private AgentShareLink requireLink(String agentId, String linkId) {
        return linkRepository.findById(linkId)
                .filter(l -> agentId.equals(l.getAgentId()))
                .orElseThrow(() -> new IllegalArgumentException("分享链接不存在"));
    }

    private void requirePublicAllowed(String mode) {
        if (AgentShareLink.MODE_PUBLIC.equals(mode) && !settingService.sharePublicEnabled()) {
            throw new IllegalArgumentException("「公开」链接未开放：需要超级管理员在平台设置中允许后才能使用");
        }
    }

    static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentShareLink.MODE_ACCESS_CODE;
        }
        String mode = raw.trim().toUpperCase();
        if (!AgentShareLink.MODE_ACCESS_CODE.equals(mode) && !AgentShareLink.MODE_PUBLIC.equals(mode)) {
            throw new IllegalArgumentException("访问方式只能是访问码或公开");
        }
        return mode;
    }

    /** 访问码：不填时生成 6 位数字；自己填时 4～32 位，只能是字母和数字 */
    static String validAccessCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return String.format("%06d", RANDOM.nextInt(1_000_000));
        }
        String code = raw.trim();
        if (code.length() < 4 || code.length() > 32 || !code.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("访问码需为 4～32 位字母或数字");
        }
        return code;
    }

    static boolean matchesAccessCode(String raw, String hash) {
        return raw != null && hash != null && ENCODER.matches(raw.trim(), hash);
    }

    static LocalDateTime resolveExpiry(LinkRequest request, LocalDateTime now, int defaultDays) {
        LocalDateTime expiresAt;
        if (request.expiresAt() != null) {
            expiresAt = request.expiresAt();
        } else {
            int days = request.expiresInDays() != null ? request.expiresInDays() : defaultDays;
            if (days < 1) {
                throw new IllegalArgumentException("有效期至少 1 天");
            }
            expiresAt = now.plusDays(days);
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("到期时间需晚于现在");
        }
        if (expiresAt.isAfter(now.plusDays(MAX_EXPIRE_DAYS))) {
            throw new IllegalArgumentException("有效期最长 " + MAX_EXPIRE_DAYS + " 天");
        }
        return expiresAt;
    }

    private static Integer positiveOrNull(Integer value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("上限不能为负数；填 0 表示不限");
        }
        return value;
    }

    private static Long positiveOrNull(Long value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("上限不能为负数；填 0 表示不限");
        }
        return value;
    }

    private static int validRpm(int rpm) {
        if (rpm < 1 || rpm > MAX_VISITOR_RPM) {
            throw new IllegalArgumentException("每个访客每分钟消息数需在 1～" + MAX_VISITOR_RPM + " 之间");
        }
        return rpm;
    }

    private static void applyDisplay(AgentShareLink link, LinkRequest request) {
        if (request.title() != null) {
            link.setTitle(request.title().isBlank() ? null : limit(request.title().trim(), 100));
        }
        if (request.welcomeMessage() != null) {
            link.setWelcomeMessage(request.welcomeMessage().isBlank() ? null : limit(request.welcomeMessage().trim(), 1000));
        }
        if (request.exampleQuestions() != null) {
            List<String> examples = new ArrayList<>();
            for (String q : request.exampleQuestions()) {
                if (q != null && !q.isBlank() && examples.size() < MAX_EXAMPLES) {
                    examples.add(limit(q.trim(), 100));
                }
            }
            link.setExampleQuestions(examples);
        }
        if (request.showRecordNotice() != null) {
            link.setShowRecordNotice(request.showRecordNotice());
        }
    }

    private String newUniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = randomCode();
            if (!linkRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("生成链接码失败，请重试");
    }

    /** 144 位随机数，URL 安全的 24 个字符 */
    static String randomCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
