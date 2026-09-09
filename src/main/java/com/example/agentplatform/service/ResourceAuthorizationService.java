package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentTemplate;
import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.KnowledgeBase;
import com.example.agentplatform.model.ResourceGrant;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.model.UserStatus;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AgentTemplateRepository;
import com.example.agentplatform.repository.KnowledgeBaseRepository;
import com.example.agentplatform.repository.ResourceGrantRepository;
import com.example.agentplatform.repository.UserRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ResourceAuthorizationService {

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String TYPE_TEMPLATE = "TEMPLATE";

    public static final String LEVEL_VIEW = "VIEW";
    public static final String LEVEL_RUN = "RUN";
    public static final String LEVEL_USE = "USE";

    private final ResourceGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AgentTemplateRepository templateRepository;

    public ResourceAuthorizationService(ResourceGrantRepository grantRepository,
                                        UserRepository userRepository,
                                        AgentRepository agentRepository,
                                        KnowledgeBaseRepository knowledgeBaseRepository,
                                        AgentTemplateRepository templateRepository) {
        this.grantRepository = grantRepository;
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.templateRepository = templateRepository;
    }

    // ==================== Agent 权限判定 ====================

    @Transactional(readOnly = true)
    public boolean canManageAgent(CurrentActor actor, Agent agent) {
        if (actor == null || agent == null || actor.isViewer()) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(agent.getIsSystem())) {
            return false;
        }
        return actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId());
    }

    @Transactional(readOnly = true)
    public boolean canCopyAgent(CurrentActor actor, Agent agent) {
        if (actor == null || agent == null || actor.isViewer()) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(agent.getIsSystem())) {
            return true;
        }
        return actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId());
    }

    @Transactional(readOnly = true)
    public boolean canRunAgent(CurrentActor actor, Agent agent) {
        if (actor == null || agent == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (actor.isViewer()) {
            return false;
        }
        if (Boolean.TRUE.equals(agent.getIsSystem())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_AGENT, agent.getId(), actor.getUserId(), Set.of(LEVEL_RUN)
        );
    }

    @Transactional(readOnly = true)
    public boolean canViewAgent(CurrentActor actor, Agent agent) {
        if (actor == null || agent == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(agent.getIsSystem())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(agent.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_AGENT, agent.getId(), actor.getUserId(), Set.of(LEVEL_VIEW, LEVEL_RUN)
        );
    }

    // ==================== KnowledgeBase 权限判定 ====================

    @Transactional(readOnly = true)
    public boolean canManageKnowledgeBase(CurrentActor actor, KnowledgeBase kb) {
        if (actor == null || kb == null || actor.isViewer()) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            return false;
        }
        return actor.getUserId() != null && actor.getUserId().equals(kb.getOwnerId());
    }

    @Transactional(readOnly = true)
    public boolean canUseKnowledgeBase(CurrentActor actor, KnowledgeBase kb) {
        if (actor == null || kb == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (actor.isViewer()) {
            return false;
        }
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(kb.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_KNOWLEDGE_BASE, kb.getId(), actor.getUserId(), Set.of(LEVEL_USE)
        );
    }

    @Transactional(readOnly = true)
    public boolean canViewKnowledgeBase(CurrentActor actor, KnowledgeBase kb) {
        if (actor == null || kb == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(kb.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_KNOWLEDGE_BASE, kb.getId(), actor.getUserId(), Set.of(LEVEL_VIEW, LEVEL_USE)
        );
    }

    // ==================== Template 权限判定 ====================

    @Transactional(readOnly = true)
    public boolean canManageTemplate(CurrentActor actor, AgentTemplate template) {
        if (actor == null || template == null || actor.isViewer()) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(template.getIsBuiltin())) {
            return false;
        }
        return actor.getUserId() != null && actor.getUserId().equals(template.getOwnerId());
    }

    @Transactional(readOnly = true)
    public boolean canUseTemplate(CurrentActor actor, AgentTemplate template) {
        if (actor == null || template == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (actor.isViewer()) {
            return false;
        }
        if (Boolean.TRUE.equals(template.getIsBuiltin())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(template.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_TEMPLATE, template.getId(), actor.getUserId(), Set.of(LEVEL_USE)
        );
    }

    @Transactional(readOnly = true)
    public boolean canViewTemplate(CurrentActor actor, AgentTemplate template) {
        if (actor == null || template == null) {
            return false;
        }
        if (actor.isSuperAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(template.getIsBuiltin())) {
            return true;
        }
        if (actor.getUserId() != null && actor.getUserId().equals(template.getOwnerId())) {
            return true;
        }
        return grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                TYPE_TEMPLATE, template.getId(), actor.getUserId(), Set.of(LEVEL_USE)
        );
    }

    // ==================== 依赖完整性校验 ====================

    /**
     * 严格校验智能体绑定的知识库权限：
     * 智能体所属所有者必须对绑定的每一个知识库拥有有效的 USE 权限；
     * 若未授权或授权已撤销，立即阻断，禁止调用底层 LLM 或 Dify。
     */
    @Transactional(readOnly = true)
    public void checkAgentKnowledgeBaseDependencies(Agent agent) {
        if (agent == null || agent.getKnowledgeBaseIds() == null || agent.getKnowledgeBaseIds().isEmpty()) {
            return;
        }
        String ownerUserId = agent.getOwnerId();
        boolean isSystemAgent = Boolean.TRUE.equals(agent.getIsSystem());

        for (String kbId : agent.getKnowledgeBaseIds()) {
            if (kbId == null || kbId.isBlank()) continue;
            KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                    .orElseThrow(() -> new IllegalStateException("依赖检查失败：绑定的知识库不存在 [" + kbId + "]，禁止调用！"));

            if (Boolean.FALSE.equals(kb.getEnabled())) {
                throw new IllegalStateException("依赖检查失败：知识库 [" + kb.getName() + "] 已停用，禁止调用！");
            }

            if (Boolean.TRUE.equals(kb.getIsSystem())) {
                continue; // 公共知识库全员可用
            }

            if (isSystemAgent) {
                continue;
            }

            if (ownerUserId != null && ownerUserId.equals(kb.getOwnerId())) {
                continue; // 属于自己的私有知识库
            }

            // 检查是否有有效的 USE 授权
            boolean hasUseGrant = ownerUserId != null && grantRepository.existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(
                    TYPE_KNOWLEDGE_BASE, kb.getId(), ownerUserId, Set.of(LEVEL_USE)
            );
            if (!hasUseGrant) {
                throw new IllegalStateException("依赖检查失败：知识库 [" + kb.getName() + "] 未获得使用授权或授权已被撤销，禁止调用！");
            }
        }
    }

    // ==================== 授权管理接口 ====================

    @Transactional(readOnly = true)
    public List<ResourceGrant> listGrants(String resourceType, String resourceId, CurrentActor actor) {
        validateManagePermission(resourceType, resourceId, actor);
        return grantRepository.findByResourceTypeAndResourceId(resourceType, resourceId);
    }

    public ResourceGrant grantAccess(String resourceType, String resourceId, String granteeUserId, String level, CurrentActor actor) {
        validateManagePermission(resourceType, resourceId, actor);

        if (actor.getUserId() != null && actor.getUserId().equals(granteeUserId)) {
            throw new IllegalArgumentException("无需对自己拥有的资源进行授权共享");
        }

        AppUser grantee = userRepository.findById(granteeUserId)
                .orElseThrow(() -> new IllegalArgumentException("被授权用户不存在: " + granteeUserId));

        if (!UserStatus.ACTIVE.equals(grantee.getStatus())) {
            throw new IllegalArgumentException("不能向非正常激活状态的用户共享授权");
        }

        String normalizedLevel = level != null ? level.trim().toUpperCase() : "";
        validateGrantLevel(resourceType, normalizedLevel);

        Optional<ResourceGrant> existing = grantRepository.findByResourceTypeAndResourceIdAndGranteeUserId(
                resourceType, resourceId, granteeUserId
        );

        ResourceGrant grant;
        if (existing.isPresent()) {
            grant = existing.get();
            grant.setLevel(normalizedLevel);
            grant.setGrantedBy(actor.getUsername());
        } else {
            grant = new ResourceGrant(
                    resourceType,
                    resourceId,
                    grantee.getId(),
                    grantee.getUsername(),
                    normalizedLevel,
                    actor.getUsername()
            );
        }
        return grantRepository.save(grant);
    }

    public void revokeAccess(String resourceType, String resourceId, String granteeUserId, CurrentActor actor) {
        validateManagePermission(resourceType, resourceId, actor);
        grantRepository.deleteByResourceTypeAndResourceIdAndGranteeUserId(resourceType, resourceId, granteeUserId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listShareCandidates(String keyword, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            return Collections.emptyList();
        }
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : "";
        return userRepository.findAll().stream()
                .filter(u -> !u.getId().equals(actor.getUserId()))
                .filter(u -> UserStatus.ACTIVE.equals(u.getStatus()))
                .filter(u -> {
                    UserRole r = UserRole.fromRaw(u.getRole());
                    return r == UserRole.DEVELOPER || r == UserRole.VIEWER;
                })
                .filter(u -> {
                    if (kw.isEmpty()) return true;
                    boolean uMatch = u.getUsername() != null && u.getUsername().toLowerCase().contains(kw);
                    boolean nMatch = u.getNickname() != null && u.getNickname().toLowerCase().contains(kw);
                    return uMatch || nMatch;
                })
                .limit(20)
                .map(u -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", u.getId());
                    map.put("username", u.getUsername());
                    map.put("nickname", u.getNickname() != null ? u.getNickname() : u.getUsername());
                    map.put("role", u.getRole());
                    return map;
                })
                .collect(Collectors.toList());
    }

    private void validateManagePermission(String resourceType, String resourceId, CurrentActor actor) {
        if (actor == null || actor.isViewer()) {
            throw new IllegalStateException("权限不足：只读用户无法管理资源授权");
        }
        if (actor.isSuperAdmin()) {
            return;
        }
        switch (resourceType) {
            case TYPE_AGENT -> {
                Agent a = agentRepository.findById(resourceId)
                        .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + resourceId));
                if (!canManageAgent(actor, a)) {
                    throw new IllegalStateException("权限不足：仅所有者可管理该智能体的共享授权");
                }
            }
            case TYPE_KNOWLEDGE_BASE -> {
                KnowledgeBase kb = knowledgeBaseRepository.findById(resourceId)
                        .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + resourceId));
                if (!canManageKnowledgeBase(actor, kb)) {
                    throw new IllegalStateException("权限不足：仅所有者可管理该知识库的共享授权");
                }
            }
            case TYPE_TEMPLATE -> {
                AgentTemplate t = templateRepository.findById(resourceId)
                        .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + resourceId));
                if (!canManageTemplate(actor, t)) {
                    throw new IllegalStateException("权限不足：仅所有者可管理该模板的共享授权");
                }
            }
            default -> throw new IllegalArgumentException("不支持的资源类型: " + resourceType);
        }
    }

    private void validateGrantLevel(String resourceType, String level) {
        switch (resourceType) {
            case TYPE_AGENT -> {
                if (!LEVEL_VIEW.equals(level) && !LEVEL_RUN.equals(level)) {
                    throw new IllegalArgumentException("智能体共享级别仅支持 VIEW 或 RUN");
                }
            }
            case TYPE_KNOWLEDGE_BASE -> {
                if (!LEVEL_VIEW.equals(level) && !LEVEL_USE.equals(level)) {
                    throw new IllegalArgumentException("知识库共享级别仅支持 VIEW 或 USE");
                }
            }
            case TYPE_TEMPLATE -> {
                if (!LEVEL_USE.equals(level)) {
                    throw new IllegalArgumentException("场景模板共享级别仅支持 USE");
                }
            }
            default -> throw new IllegalArgumentException("不支持的资源类型: " + resourceType);
        }
    }

    @Transactional(readOnly = true)
    public Set<String> getGrantedResourceIds(String resourceType, String granteeUserId, Collection<String> levels) {
        if (granteeUserId == null || granteeUserId.isBlank()) {
            return Collections.emptySet();
        }
        return grantRepository.findByResourceTypeAndGranteeUserId(resourceType, granteeUserId).stream()
                .filter(g -> levels == null || levels.isEmpty() || levels.contains(g.getLevel()))
                .map(ResourceGrant::getResourceId)
                .collect(Collectors.toSet());
    }
}
