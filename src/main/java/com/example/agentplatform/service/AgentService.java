package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.DashboardStats;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AgentRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AgentService {

    private final AgentRepository agentRepository;

    public AgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public PageResult<Agent> searchAgents(String keyword, String category, AgentStatus status, int page, int size) {
        List<Agent> all = agentRepository.findAll();

        List<Agent> filtered = all.stream()
                .filter(a -> {
                    if (keyword != null && !keyword.trim().isEmpty()) {
                        String kw = keyword.trim().toLowerCase();
                        boolean matchName = a.getName() != null && a.getName().toLowerCase().contains(kw);
                        boolean matchDesc = a.getDescription() != null && a.getDescription().toLowerCase().contains(kw);
                        boolean matchCode = a.getCode() != null && a.getCode().toLowerCase().contains(kw);
                        boolean matchTag = a.getTags() != null && a.getTags().stream().anyMatch(t -> t.toLowerCase().contains(kw));
                        if (!matchName && !matchDesc && !matchCode && !matchTag) {
                            return false;
                        }
                    }
                    if (category != null && !category.trim().isEmpty() && !"全部".equals(category.trim())) {
                        if (!category.trim().equalsIgnoreCase(a.getCategory())) {
                            return false;
                        }
                    }
                    if (status != null) {
                        if (a.getStatus() != status) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        int total = filtered.size();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);

        List<Agent> pageRecords = filtered.subList(fromIndex, toIndex);
        return new PageResult<>(pageRecords, total, safePage, safeSize);
    }

    public Optional<Agent> getById(String id) {
        return agentRepository.findById(id);
    }

    public Agent create(Agent agent) {
        return agentRepository.save(agent);
    }

    public Agent update(String id, Agent agentUpdate) {
        Agent existing = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + id));

        if (agentUpdate.getName() != null) existing.setName(agentUpdate.getName());
        if (agentUpdate.getCode() != null) existing.setCode(agentUpdate.getCode());
        if (agentUpdate.getAvatar() != null) existing.setAvatar(agentUpdate.getAvatar());
        if (agentUpdate.getCategory() != null) existing.setCategory(agentUpdate.getCategory());
        if (agentUpdate.getDescription() != null) existing.setDescription(agentUpdate.getDescription());
        if (agentUpdate.getModelName() != null) existing.setModelName(agentUpdate.getModelName());
        if (agentUpdate.getSystemPrompt() != null) existing.setSystemPrompt(agentUpdate.getSystemPrompt());
        if (agentUpdate.getTemperature() != null) existing.setTemperature(agentUpdate.getTemperature());
        if (agentUpdate.getTopP() != null) existing.setTopP(agentUpdate.getTopP());
        if (agentUpdate.getMaxTokens() != null) existing.setMaxTokens(agentUpdate.getMaxTokens());
        if (agentUpdate.getTags() != null) existing.setTags(agentUpdate.getTags());
        if (agentUpdate.getStatus() != null) existing.setStatus(agentUpdate.getStatus());

        return agentRepository.save(existing);
    }

    public boolean delete(String id) {
        return agentRepository.deleteById(id);
    }

    public Agent toggleStatus(String id) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + id));

        if (agent.getStatus() == AgentStatus.RUNNING) {
            agent.setStatus(AgentStatus.DISABLED);
        } else if (agent.getStatus() == AgentStatus.DISABLED) {
            agent.setStatus(AgentStatus.RUNNING);
        } else {
            agent.setStatus(AgentStatus.RUNNING);
        }
        return agentRepository.save(agent);
    }

    public void incrementCallCount(String id, long latencyMs) {
        agentRepository.findById(id).ifPresent(agent -> {
            long currentCount = agent.getCallCount() == null ? 0 : agent.getCallCount();
            agent.setCallCount(currentCount + 1);
            double currentAvg = agent.getAvgResponseTimeMs() == null ? 300.0 : agent.getAvgResponseTimeMs();
            agent.setAvgResponseTimeMs(Math.round((currentAvg * 0.8 + latencyMs * 0.2) * 10.0) / 10.0);
            agentRepository.save(agent);
        });
    }

    public DashboardStats getDashboardStats() {
        List<Agent> all = agentRepository.findAll();

        DashboardStats stats = new DashboardStats();
        stats.setTotalAgents(all.size());
        stats.setRunningAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.RUNNING).count());
        stats.setIdleAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.IDLE).count());
        stats.setDisabledAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.DISABLED).count());

        long totalCalls = all.stream().mapToLong(a -> a.getCallCount() == null ? 0 : a.getCallCount()).sum();
        stats.setTotalCalls(totalCalls);

        double avgLatency = all.stream()
                .mapToDouble(a -> a.getAvgResponseTimeMs() == null ? 300.0 : a.getAvgResponseTimeMs())
                .average()
                .orElse(320.0);
        stats.setAvgResponseTimeMs(Math.round(avgLatency * 10.0) / 10.0);
        stats.setSuccessRate(99.4);

        // 分类分布
        Map<String, Long> categoryCount = all.stream()
                .collect(Collectors.groupingBy(a -> a.getCategory() == null ? "其它" : a.getCategory(), Collectors.counting()));
        stats.setCategoryDistribution(categoryCount);

        // 模型分布
        Map<String, Long> modelCount = all.stream()
                .collect(Collectors.groupingBy(a -> a.getModelName() == null ? "未指定" : a.getModelName(), Collectors.counting()));
        stats.setModelDistribution(modelCount);

        return stats;
    }

    public List<Map<String, Object>> getPresetTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();

        templates.add(Map.of(
                "name", "代码审计与重构专家",
                "avatar", "💻",
                "category", "代码研发",
                "modelName", "gpt-4o",
                "description", "专注于识别代码坏味道、潜在漏洞、并发竞态并提供重构改进建议。",
                "systemPrompt", "你是一名资深代码审计与架构师。分析用户提交的代码，识别潜在 Bug、安全性漏洞、性能瓶颈及不符合 SOLID 原则的地方，并给出重构后的标准代码与详细解释。",
                "temperature", 0.2,
                "tags", List.of("代码审计", "重构", "安全规范")
        ));

        templates.add(Map.of(
                "name", "智能技术文档撰写者",
                "avatar", "📚",
                "category", "内容创作",
                "modelName", "gpt-4o-mini",
                "description", "根据接口代码或系统架构设计，自动生成高质量的 Markdown 技术文档与 API 说明。",
                "systemPrompt", "你是一名顶级技术作家（Technical Writer）。擅长使用清晰、严谨且易于阅读的 Markdown 格式撰写 API 规范、架构设计说明、系统部署手册等技术文档。",
                "temperature", 0.4,
                "tags", List.of("技术文档", "API手册", "Markdown")
        ));

        templates.add(Map.of(
                "name", "数据分析与 BI 洞察助手",
                "avatar", "📊",
                "category", "数据分析",
                "modelName", "deepseek-chat",
                "description", "对业务数据进行多维度下钻分析，提取核心指标变化原因与商业决策建议。",
                "systemPrompt", "你是一名资深商业数据分析师(BI)。请根据用户提供的数据集或业务指标，给出多维度统计分析、异常值检测、趋势预测及切实可行的商业优化策略。",
                "temperature", 0.3,
                "tags", List.of("数据分析", "BI", "商业洞察")
        ));

        return templates;
    }
}
