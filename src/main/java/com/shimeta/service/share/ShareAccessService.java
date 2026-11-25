package com.shimeta.service.share;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shimeta.model.Agent;
import com.shimeta.model.AgentShareLink;
import com.shimeta.model.AgentShareVisitor;
import com.shimeta.model.AgentStatus;
import com.shimeta.model.AppUser;
import com.shimeta.model.UserStatus;
import com.shimeta.repository.AgentRepository;
import com.shimeta.repository.AgentShareLinkRepository;
import com.shimeta.repository.AgentShareVisitorRepository;
import com.shimeta.repository.UserRepository;
import com.shimeta.service.AuditRecorder;
import com.shimeta.service.OpenApiKeyService;
import com.shimeta.service.PlatformSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 访客一侧：打开链接、校验访问码换访客令牌、每次请求重新校验链接。
 * 链接不可用的原因只写日志，对访客统一显示「链接已失效」
 */
@Service
public class ShareAccessService {

    private static final Logger log = LoggerFactory.getLogger(ShareAccessService.class);

    static final Duration TOKEN_TTL = Duration.ofHours(2);
    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /** 同一链接在锁定时间内的失败总数上限：防止换 IP 或伪造 X-Forwarded-For 绕过单个访客的锁定 */
    static final int MAX_LINK_FAILURES = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentShareLinkRepository linkRepository;
    private final AgentShareVisitorRepository visitorRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final OpenApiKeyService keyService;
    private final PlatformSettingService settingService;
    private final AuditRecorder auditRecorder;

    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(LOCK_DURATION).maximumSize(100_000).build();
    private final Cache<String, LocalDateTime> locks = Caffeine.newBuilder()
            .expireAfterWrite(LOCK_DURATION).maximumSize(100_000).build();

    public ShareAccessService(AgentShareLinkRepository linkRepository,
                              AgentShareVisitorRepository visitorRepository,
                              AgentRepository agentRepository,
                              UserRepository userRepository,
                              OpenApiKeyService keyService,
                              PlatformSettingService settingService,
                              AuditRecorder auditRecorder) {
        this.linkRepository = linkRepository;
        this.visitorRepository = visitorRepository;
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.keyService = keyService;
        this.settingService = settingService;
        this.auditRecorder = auditRecorder;
    }

    /** 一次通过校验的访客请求 */
    public record VisitorSession(AgentShareLink link, Agent agent, AgentShareVisitor visitor, AppUser owner) {
    }

    /** 访问码校验通过后返回给访客的令牌 */
    public record IssuedToken(String visitorId, String token, LocalDateTime expiresAt) {
    }

    /** 打开链接时的公开信息：链接不可用时抛 linkInvalid，不返回任何内容 */
    public AgentShareLink requireActiveLink(String code) {
        return activeLink(code).orElseThrow(ShareAccessException::linkInvalid);
    }

    /**
     * 校验访问码（公开链接不需要），通过后签发 2 小时有效的访客令牌。
     *
     * @param visitorId 浏览器里保存的访客 ID；属于这个链接时沿用，否则新建访客（占用访客名额）
     */
    public IssuedToken startSession(String code, String visitorId, String accessCode, String ip) {
        AgentShareLink link = requireActiveLink(code);
        String ipKey = link.getId() + "|ip|" + (ip == null ? "" : ip);
        String linkKey = link.getId() + "|all";
        LocalDateTime lockedUntil = lockedUntil(ipKey, linkKey);
        if (lockedUntil != null) {
            long seconds = Math.max(1, Duration.between(LocalDateTime.now(), lockedUntil).getSeconds());
            throw new ShareAccessException(ShareAccessException.TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS,
                    "访问码错误次数过多，请 " + Math.max(1, (seconds + 59) / 60) + " 分钟后再试", seconds);
        }
        if (AgentShareLink.MODE_ACCESS_CODE.equals(link.getAccessMode())
                && !ShareLinkService.matchesAccessCode(accessCode, link.getAccessCodeHash())) {
            recordFailure(link, ipKey, linkKey);
            throw new ShareAccessException(ShareAccessException.ACCESS_CODE_INVALID, HttpStatus.UNAUTHORIZED, "访问码不正确");
        }
        failures.invalidate(ipKey);

        LocalDateTime now = LocalDateTime.now();
        AgentShareVisitor visitor = visitorId == null || visitorId.isBlank() ? null
                : visitorRepository.findById(visitorId.trim()).filter(v -> link.getId().equals(v.getLinkId())).orElse(null);
        if (visitor == null) {
            if (linkRepository.reserveVisitor(link.getId()) == 0) {
                throw new ShareAccessException(ShareAccessException.VISITOR_LIMIT, HttpStatus.FORBIDDEN,
                        "该链接的访客人数已满，请联系提供方");
            }
            visitor = new AgentShareVisitor();
            visitor.setId(randomHex(16));
            visitor.setLinkId(link.getId());
            visitor.setFirstSeenAt(now);
        }
        String token = randomHex(32);
        visitor.setTokenHash(OpenApiKeyService.sha256(token));
        visitor.setTokenExpiresAt(now.plus(TOKEN_TTL));
        visitor.setLastSeenAt(now);
        visitor.setLastIp(limit(ip, 64));
        visitorRepository.save(visitor);
        return new IssuedToken(visitor.getId(), token, visitor.getTokenExpiresAt());
    }

    /**
     * 每次访客请求都调用：链接（按地址里的链接码）当前可用、令牌属于这个链接且未过期。
     * 链接不可用时抛 linkInvalid；令牌无效或过期时抛 sessionExpired（页面回到访问码输入）
     */
    public VisitorSession authenticate(String code, String token) {
        AgentShareLink link = requireActiveLink(code);
        if (token == null || token.isBlank()) {
            throw ShareAccessException.sessionExpired();
        }
        AgentShareVisitor visitor = visitorRepository.findByTokenHash(OpenApiKeyService.sha256(token.trim()))
                .filter(v -> link.getId().equals(v.getLinkId()))
                .filter(v -> v.getTokenExpiresAt() != null && LocalDateTime.now().isBefore(v.getTokenExpiresAt()))
                .orElseThrow(ShareAccessException::sessionExpired);
        Agent agent = agentRepository.findById(link.getAgentId()).orElseThrow(ShareAccessException::linkInvalid);
        AppUser owner = keyService.findUsableManaged(link.getApiKeyId())
                .flatMap(key -> userRepository.findById(key.getOwnerId()))
                .filter(u -> !UserStatus.DISABLED.equals(u.getStatus()))
                .orElseGet(() -> {
                    log.warn("Share link {} has no usable internal key or its owner is disabled", link.getId());
                    throw ShareAccessException.linkInvalid();
                });
        return new VisitorSession(link, agent, visitor, owner);
    }

    /** 链接对访客是否可用：启用、未过期、用量未用完、公开链接被允许、智能体在运行 */
    Optional<AgentShareLink> activeLink(String code) {
        if (code == null || code.isBlank() || code.length() > 64) {
            return Optional.empty();
        }
        Optional<AgentShareLink> found = linkRepository.findByCode(code.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AgentShareLink link = found.get();
        Agent agent = agentRepository.findById(link.getAgentId()).orElse(null);
        String state = ShareLinkService.effectiveState(link, agent, settingService.sharePublicEnabled(), LocalDateTime.now());
        if (!"ACTIVE".equals(state)) {
            log.info("Share link {} rejected: {}", link.getId(), state);
            return Optional.empty();
        }
        if (agent.getStatus() != AgentStatus.RUNNING) {
            return Optional.empty();
        }
        return Optional.of(link);
    }

    private LocalDateTime lockedUntil(String ipKey, String linkKey) {
        LocalDateTime now = LocalDateTime.now();
        for (String key : new String[]{ipKey, linkKey}) {
            LocalDateTime until = locks.getIfPresent(key);
            if (until != null && now.isBefore(until)) {
                return until;
            }
        }
        return null;
    }

    private void recordFailure(AgentShareLink link, String ipKey, String linkKey) {
        int ipCount = failures.get(ipKey, k -> new AtomicInteger()).incrementAndGet();
        int linkCount = failures.get(linkKey, k -> new AtomicInteger()).incrementAndGet();
        LocalDateTime until = LocalDateTime.now().plus(LOCK_DURATION);
        if (ipCount >= MAX_FAILURES) {
            locks.put(ipKey, until);
            failures.invalidate(ipKey);
            auditRecorder.record("share_link.locked", "SHARE_LINK", link.getId(), "DENIED", "access_code_attempts",
                    "MEDIUM", "visitor locked for " + LOCK_DURATION.toMinutes() + " minutes");
        }
        if (linkCount >= MAX_LINK_FAILURES) {
            locks.put(linkKey, until);
            failures.invalidate(linkKey);
            auditRecorder.record("share_link.locked", "SHARE_LINK", link.getId(), "DENIED", "access_code_flood",
                    "HIGH", "link verification locked for " + LOCK_DURATION.toMinutes() + " minutes");
        }
    }

    static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
