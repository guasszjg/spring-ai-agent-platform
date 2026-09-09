package com.example.agentplatform.service;

import com.example.agentplatform.model.AgentTemplate;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AgentTemplateRepository;
import com.example.agentplatform.repository.ResourceGrantRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class AgentTemplateService {

    private final AgentTemplateRepository templateRepository;
    private final ResourceAuthorizationService resourceAuthService;
    private final ResourceGrantRepository grantRepository;

    public AgentTemplateService(AgentTemplateRepository templateRepository,
                                ResourceAuthorizationService resourceAuthService,
                                ResourceGrantRepository grantRepository) {
        this.templateRepository = templateRepository;
        this.resourceAuthService = resourceAuthService;
        this.grantRepository = grantRepository;
    }

    @Transactional(readOnly = true)
    public List<AgentTemplate> list(String keyword, String category) {
        return list(keyword, category, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public List<AgentTemplate> list(String keyword, String category, CurrentActor actor) {
        List<AgentTemplate> all = templateRepository.searchTemplates(keyword, category);
        return all.stream()
                .filter(t -> actor == null || resourceAuthService.canViewTemplate(actor, t))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PageResult<AgentTemplate> searchTemplates(String keyword, String category, int page, int size) {
        return searchTemplates(keyword, category, page, size, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public PageResult<AgentTemplate> searchTemplates(String keyword, String category, int page, int size, CurrentActor actor) {
        List<AgentTemplate> filtered = list(keyword, category, actor);
        int total = filtered.size();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        List<AgentTemplate> records = filtered.subList(fromIndex, toIndex);
        return new PageResult<>(records, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public Optional<AgentTemplate> getById(String id) {
        return getById(id, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public Optional<AgentTemplate> getById(String id, CurrentActor actor) {
        Optional<AgentTemplate> opt = templateRepository.findById(id);
        if (opt.isPresent() && actor != null && !resourceAuthService.canViewTemplate(actor, opt.get())) {
            throw new IllegalStateException("权限不足：无权访问该模板");
        }
        return opt;
    }

    public AgentTemplate create(AgentTemplate template) {
        return create(template, CurrentActor.get());
    }

    public AgentTemplate create(AgentTemplate template, CurrentActor actor) {
        if (actor != null && actor.isViewer()) {
            throw new IllegalStateException("权限不足：只读观察员无权创建模板");
        }
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (template.getSystemPrompt() == null || template.getSystemPrompt().trim().isEmpty()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        template.setId(null);
        if (actor != null) {
            template.setOwnerId(actor.getUserId());
            template.setOwnerUsername(actor.getUsername());
            if (!actor.isSuperAdmin()) {
                template.setIsBuiltin(false);
            }
        } else {
            template.setIsBuiltin(false);
        }
        return templateRepository.save(template);
    }

    public AgentTemplate update(String id, AgentTemplate update) {
        return update(id, update, CurrentActor.get());
    }

    public AgentTemplate update(String id, AgentTemplate update, CurrentActor actor) {
        AgentTemplate existing = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));

        if (!resourceAuthService.canManageTemplate(actor, existing)) {
            throw new IllegalStateException("权限不足：无权修改该模板");
        }

        if (update.getName() != null && !update.getName().trim().isEmpty()) {
            existing.setName(update.getName().trim());
        }
        if (update.getCategory() != null) {
            existing.setCategory(update.getCategory().trim());
        }
        if (update.getAvatar() != null) {
            existing.setAvatar(update.getAvatar().trim());
        }
        if (update.getDescription() != null) {
            existing.setDescription(update.getDescription().trim());
        }
        if (update.getModelName() != null) {
            existing.setModelName(update.getModelName().trim());
        }
        if (update.getSystemPrompt() != null && !update.getSystemPrompt().trim().isEmpty()) {
            existing.setSystemPrompt(update.getSystemPrompt().trim());
        }
        if (update.getTemperature() != null) {
            existing.setTemperature(update.getTemperature());
        }
        if (update.getTopP() != null) {
            existing.setTopP(update.getTopP());
        }
        if (update.getMaxTokens() != null) {
            existing.setMaxTokens(update.getMaxTokens());
        }
        if (update.getTags() != null) {
            existing.setTags(update.getTags().trim());
        }
        if (update.getSortOrder() != null) {
            existing.setSortOrder(update.getSortOrder());
        }

        return templateRepository.save(existing);
    }

    public boolean delete(String id) {
        return delete(id, CurrentActor.get());
    }

    public boolean delete(String id, CurrentActor actor) {
        Optional<AgentTemplate> opt = templateRepository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        AgentTemplate existing = opt.get();
        if (!resourceAuthService.canManageTemplate(actor, existing)) {
            throw new IllegalStateException("权限不足：无权删除该模板");
        }
        if (grantRepository != null) {
            grantRepository.deleteByResourceTypeAndResourceId(ResourceAuthorizationService.TYPE_TEMPLATE, id);
        }
        templateRepository.delete(existing);
        return true;
    }
}
