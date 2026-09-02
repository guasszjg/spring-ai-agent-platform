package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.AgentDailyStat;
import com.example.agentplatform.model.AgentMonitorStats;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.DashboardStats;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.AgentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class AgentService {

    private static final DateTimeFormatter TREND_LABEL = DateTimeFormatter.ofPattern("M/d");
    private static final double USD_PER_MILLION_TOKENS = 1.55;

    private final AgentRepository agentRepository;
    private final AgentDailyStatRepository dailyStatRepository;
    private final AgentConversationService conversationService;

    public AgentService(AgentRepository agentRepository,
                        AgentDailyStatRepository dailyStatRepository,
                        AgentConversationService conversationService) {
        this.agentRepository = agentRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.conversationService = conversationService;
    }

    @Transactional(readOnly = true)
    public PageResult<Agent> searchAgents(String keyword, String category, AgentStatus status, int page, int size) {
        List<Agent> all = agentRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));

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

    @Transactional(readOnly = true)
    public Optional<Agent> getById(String id) {
        return agentRepository.findById(id);
    }

    public Agent create(Agent agent) {
        agent.setId(null);
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
        if (agentUpdate.getTags() != null) {
            existing.getTags().clear();
            existing.getTags().addAll(agentUpdate.getTags());
        }
        if (agentUpdate.getStatus() != null) existing.setStatus(agentUpdate.getStatus());

        return agentRepository.save(existing);
    }

    public boolean delete(String id) {
        if (!agentRepository.existsById(id)) {
            return false;
        }
        agentRepository.deleteById(id);
        return true;
    }

    public Agent updateStatus(String id, AgentStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + id));
        agent.setStatus(status);
        return agentRepository.save(agent);
    }

    public void recordInvocation(String id, long latencyMs, long promptTokens, long completionTokens, boolean success) {
        LocalDate today = LocalDate.now();
        AgentDailyStat stat = dailyStatRepository.findByAgentIdAndStatDate(id, today)
                .orElseGet(() -> {
                    AgentDailyStat created = new AgentDailyStat();
                    created.setId(id + "-" + today);
                    created.setAgentId(id);
                    created.setStatDate(today);
                    return created;
                });
        stat.setCallCount(stat.getCallCount() + 1);
        stat.setPromptTokens(stat.getPromptTokens() + Math.max(0, promptTokens));
        stat.setCompletionTokens(stat.getCompletionTokens() + Math.max(0, completionTokens));
        stat.setTotalLatencyMs(stat.getTotalLatencyMs() + Math.max(0, latencyMs));
        if (success) {
            stat.setSuccessCount(stat.getSuccessCount() + 1);
        }
        dailyStatRepository.save(stat);
    }

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats() {
        return getDashboardStats("7days");
    }

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats(String range) {
        int days = resolveRangeDays(range);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1L);

        List<Agent> all = agentRepository.findAll();
        Map<String, Agent> agentById = all.stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(Agent::getId, a -> a, (a, b) -> a));

        List<AgentDailyStat> current = dailyStatRepository.findByStatDateBetween(start, end);
        List<AgentDailyStat> previous = dailyStatRepository.findByStatDateBetween(prevStart, prevEnd);

        DashboardStats stats = new DashboardStats();
        stats.setTotalAgents(all.size());
        stats.setRunningAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.RUNNING).count());
        stats.setIdleAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.IDLE).count());
        stats.setDisabledAgents(all.stream().filter(a -> a.getStatus() == AgentStatus.DISABLED).count());

        long totalCalls = current.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        if (totalCalls == 0) {
            totalCalls = all.stream().mapToLong(a -> a.getCallCount() == null ? 0 : a.getCallCount()).sum();
        }
        stats.setTotalCalls(totalCalls);

        long latencyCalls = current.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        long latencySum = current.stream().mapToLong(AgentDailyStat::getTotalLatencyMs).sum();
        double avgLatency;
        if (latencyCalls > 0) {
            avgLatency = latencySum * 1.0 / latencyCalls;
        } else {
            avgLatency = all.stream()
                    .filter(a -> a.getAvgResponseTimeMs() != null && a.getAvgResponseTimeMs() > 0)
                    .mapToDouble(Agent::getAvgResponseTimeMs)
                    .average()
                    .orElse(0.0);
        }
        stats.setAvgResponseTimeMs(Math.round(avgLatency * 10.0) / 10.0);

        long successCount = current.stream().mapToLong(AgentDailyStat::getSuccessCount).sum();
        long successCalls = current.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        double successRate = successCalls == 0 ? 0.0 : Math.round(successCount * 1000.0 / successCalls) / 10.0;
        stats.setSuccessRate(successRate);

        Map<String, Long> categoryCount = all.stream()
                .collect(Collectors.groupingBy(a -> a.getCategory() == null ? "其它" : a.getCategory(), Collectors.counting()));
        stats.setCategoryDistribution(categoryCount);

        long promptTokens = current.stream().mapToLong(AgentDailyStat::getPromptTokens).sum();
        long completionTokens = current.stream().mapToLong(AgentDailyStat::getCompletionTokens).sum();
        stats.setPromptTokens(promptTokens);
        stats.setCompletionTokens(completionTokens);
        long totalTokens = promptTokens + completionTokens;
        stats.setEstimatedCostUsd(Math.round(totalTokens / 1_000_000.0 * USD_PER_MILLION_TOKENS * 100.0) / 100.0);

        long prevTokens = previous.stream()
                .mapToLong(s -> s.getPromptTokens() + s.getCompletionTokens())
                .sum();
        double change = prevTokens == 0 ? 0.0 : (totalTokens - prevTokens) * 100.0 / prevTokens;
        stats.setTokenChangePercent(Math.round(change * 10.0) / 10.0);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            byDay.put(d, new long[]{0L, 0L});
        }
        for (AgentDailyStat row : current) {
            long[] bucket = byDay.computeIfAbsent(row.getStatDate(), k -> new long[]{0L, 0L});
            bucket[0] += row.getPromptTokens();
            bucket[1] += row.getCompletionTokens();
        }
        List<DashboardStats.TrendPoint> trend = new ArrayList<>();
        byDay.forEach((date, values) ->
                trend.add(new DashboardStats.TrendPoint(date.format(TREND_LABEL), values[0], values[1])));
        stats.setTokenTrend(trend);

        Map<String, Long> modelTokens = new LinkedHashMap<>();
        for (AgentDailyStat row : current) {
            Agent agent = agentById.get(row.getAgentId());
            String model = agent != null && agent.getModelName() != null ? agent.getModelName() : "未指定";
            modelTokens.merge(model, row.getPromptTokens() + row.getCompletionTokens(), Long::sum);
        }
        if (modelTokens.isEmpty()) {
            modelTokens = all.stream()
                    .collect(Collectors.groupingBy(a -> a.getModelName() == null ? "未指定" : a.getModelName(), Collectors.counting()));
        }
        stats.setModelDistribution(modelTokens);

        Map<String, long[]> rankAgg = new HashMap<>();
        for (AgentDailyStat row : current) {
            long[] agg = rankAgg.computeIfAbsent(row.getAgentId(), k -> new long[]{0L, 0L});
            agg[0] += row.getCallCount();
            agg[1] += row.getPromptTokens() + row.getCompletionTokens();
        }
        List<DashboardStats.RankingItem> ranking = rankAgg.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(4)
                .map(entry -> {
                    Agent agent = agentById.get(entry.getKey());
                    DashboardStats.RankingItem item = new DashboardStats.RankingItem();
                    item.setAvatar(agent != null && agent.getAvatar() != null ? agent.getAvatar() : "🤖");
                    item.setName(agent != null ? agent.getName() : entry.getKey());
                    item.setModel(agent != null && agent.getModelName() != null ? agent.getModelName() : "未指定");
                    item.setCalls(entry.getValue()[0]);
                    item.setTokens(entry.getValue()[1]);
                    return item;
                })
                .collect(Collectors.toList());
        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).setRank(i + 1);
        }
        stats.setRanking(ranking);

        Map<String, long[]> latencyAgg = new LinkedHashMap<>();
        for (AgentDailyStat row : current) {
            Agent agent = agentById.get(row.getAgentId());
            String category = agent != null && agent.getCategory() != null ? agent.getCategory() : "其它";
            long[] agg = latencyAgg.computeIfAbsent(category, k -> new long[]{0L, 0L});
            agg[0] += row.getTotalLatencyMs();
            agg[1] += row.getCallCount();
        }
        List<DashboardStats.CategoryLatency> latencies = latencyAgg.entrySet().stream()
                .map(entry -> {
                    long calls = entry.getValue()[1];
                    double avg = calls == 0 ? 0 : Math.round(entry.getValue()[0] * 10.0 / calls) / 10.0;
                    return new DashboardStats.CategoryLatency(entry.getKey(), avg);
                })
                .collect(Collectors.toList());
        stats.setLatencyByCategory(latencies);

        return stats;
    }

    @Transactional(readOnly = true)
    public AgentMonitorStats getAgentMonitor(String agentId, String range) {
        if (!agentRepository.existsById(agentId)) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
        LocalDate[] bounds = TimeRange.resolve(range);
        LocalDate end = bounds[1] != null ? bounds[1] : LocalDate.now();
        boolean allTime = bounds[0] == null;
        LocalDate start = allTime ? null : bounds[0];

        List<AgentDailyStat> current = allTime
                ? dailyStatRepository.findByAgentId(agentId)
                : dailyStatRepository.findByAgentIdAndStatDateBetween(agentId, start, end);

        List<AgentDailyStat> previous = List.of();
        if (!allTime && start != null) {
            long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            LocalDate prevEnd = start.minusDays(1);
            LocalDate prevStart = prevEnd.minusDays(span - 1);
            previous = dailyStatRepository.findByAgentIdAndStatDateBetween(agentId, prevStart, prevEnd);
        }

        AgentMonitorStats stats = new AgentMonitorStats();
        long promptTokens = current.stream().mapToLong(AgentDailyStat::getPromptTokens).sum();
        long completionTokens = current.stream().mapToLong(AgentDailyStat::getCompletionTokens).sum();
        long totalTokens = promptTokens + completionTokens;
        stats.setPromptTokens(promptTokens);
        stats.setCompletionTokens(completionTokens);
        stats.setTotalTokens(totalTokens);
        stats.setEstimatedCostUsd(Math.round(totalTokens / 1_000_000.0 * USD_PER_MILLION_TOKENS * 100.0) / 100.0);

        long prevTokens = previous.stream()
                .mapToLong(s -> s.getPromptTokens() + s.getCompletionTokens())
                .sum();
        double change = prevTokens == 0 ? 0.0 : (totalTokens - prevTokens) * 100.0 / prevTokens;
        stats.setTokenChangePercent(Math.round(change * 10.0) / 10.0);

        long calls = current.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        long latencySum = current.stream().mapToLong(AgentDailyStat::getTotalLatencyMs).sum();
        stats.setCallCount(calls);
        stats.setAvgResponseTimeMs(calls == 0 ? 0 : Math.round(latencySum * 10.0 / calls) / 10.0);

        List<AgentConversation> sessions = conversationService.listInRange(agentId, range);
        stats.setSessionCount(sessions.size());
        stats.setMessageCount(sessions.stream().mapToLong(AgentConversation::getMessageCount).sum());

        LocalDate trendStart = start;
        if (allTime) {
            trendStart = current.stream()
                    .map(AgentDailyStat::getStatDate)
                    .min(LocalDate::compareTo)
                    .orElse(end.minusDays(6));
        }
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = trendStart; !d.isAfter(end); d = d.plusDays(1)) {
            byDay.put(d, new long[]{0L, 0L});
        }
        for (AgentDailyStat row : current) {
            long[] bucket = byDay.computeIfAbsent(row.getStatDate(), k -> new long[]{0L, 0L});
            bucket[0] += row.getPromptTokens();
            bucket[1] += row.getCompletionTokens();
        }
        List<DashboardStats.TrendPoint> trend = new ArrayList<>();
        byDay.forEach((date, values) ->
                trend.add(new DashboardStats.TrendPoint(date.format(TREND_LABEL), values[0], values[1])));
        stats.setTokenTrend(trend);
        return stats;
    }

    private int resolveRangeDays(String range) {
        if ("today".equalsIgnoreCase(range)) {
            return 1;
        }
        if ("30days".equalsIgnoreCase(range)) {
            return 30;
        }
        return 7;
    }

    @Transactional(readOnly = true)
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
