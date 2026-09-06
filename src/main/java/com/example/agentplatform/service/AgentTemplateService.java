package com.example.agentplatform.service;

import com.example.agentplatform.model.AgentTemplate;
import com.example.agentplatform.repository.AgentTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AgentTemplateService {

    private final AgentTemplateRepository templateRepository;

    public AgentTemplateService(AgentTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional(readOnly = true)
    public List<AgentTemplate> list(String keyword, String category) {
        return templateRepository.searchTemplates(keyword, category);
    }

    @Transactional(readOnly = true)
    public Optional<AgentTemplate> getById(String id) {
        return templateRepository.findById(id);
    }

    public AgentTemplate create(AgentTemplate template) {
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (template.getSystemPrompt() == null || template.getSystemPrompt().trim().isEmpty()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        template.setId(null);
        return templateRepository.save(template);
    }

    public AgentTemplate update(String id, AgentTemplate update) {
        AgentTemplate existing = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));

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
        if (templateRepository.existsById(id)) {
            templateRepository.deleteById(id);
            return true;
        }
        return false;
    }
}
