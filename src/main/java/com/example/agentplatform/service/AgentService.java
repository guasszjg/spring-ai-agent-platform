package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentConversation;
import com.example.agentplatform.model.AgentDailyStat;
import com.example.agentplatform.model.AgentMonitorStats;
import com.example.agentplatform.model.AgentStatus;
import com.example.agentplatform.model.DashboardStats;
import com.example.agentplatform.model.PageResult;
import com.example.agentplatform.config.ToolConfigSanitizer;
import com.example.agentplatform.repository.AgentDailyStatRepository;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.security.CurrentActor;
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

    private final AgentRepository agentRepository;
    private final AgentDailyStatRepository dailyStatRepository;
    private final AgentConversationService conversationService;
    private final ToolConfigSanitizer toolConfigSanitizer;
    private final AgentToolSecretService toolSecretService;
    private final ResourceAuthorizationService resourceAuthService;
    private final com.example.agentplatform.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.example.agentplatform.repository.ResourceGrantRepository resourceGrantRepository;
    private final OwnerNameResolver ownerNameResolver;

    public AgentService(AgentRepository agentRepository,
                        AgentDailyStatRepository dailyStatRepository,
                        AgentConversationService conversationService,
                        ToolConfigSanitizer toolConfigSanitizer,
                        AgentToolSecretService toolSecretService,
                        ResourceAuthorizationService resourceAuthService,
                        com.example.agentplatform.repository.KnowledgeBaseRepository knowledgeBaseRepository,
                        com.example.agentplatform.repository.ResourceGrantRepository resourceGrantRepository,
                        OwnerNameResolver ownerNameResolver) {
        this.agentRepository = agentRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.conversationService = conversationService;
        this.toolConfigSanitizer = toolConfigSanitizer;
        this.toolSecretService = toolSecretService;
        this.resourceAuthService = resourceAuthService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.resourceGrantRepository = resourceGrantRepository;
        this.ownerNameResolver = ownerNameResolver;
    }

    @Transactional(readOnly = true)
    public PageResult<Agent> searchAgents(String keyword, String category, AgentStatus status, int page, int size) {
        return searchAgents(keyword, category, status, null, CurrentActor.get(), page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<Agent> searchAgents(String keyword, String category, AgentStatus status, String scope, CurrentActor actor, int page, int size) {
        return searchAgents(keyword, category, status, scope, null, actor, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<Agent> searchAgents(String keyword, String category, AgentStatus status, String scope, String ownerId, CurrentActor actor, int page, int size) {
        List<Agent> all = agentRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
        Map<String, String> names = ownerNameResolver.usernames(
                all.stream().map(Agent::getOwnerId).collect(Collectors.toSet()));

        List<Agent> filtered = all.stream()
                .filter(a -> {
                    // 1. Role & Grant-based baseline accessibility
                    if (actor != null && !resourceAuthService.canViewAgent(actor, a)) {
                        return false;
                    }

                    // 2. Explicit scope tab filter (mine | system | shared | all)
                    if ("mine".equalsIgnoreCase(scope)) {
                        if (actor == null || actor.getUserId() == null || !actor.getUserId().equals(a.getOwnerId())) {
                            return false;
                        }
                    } else if ("system".equalsIgnoreCase(scope)) {
                        if (!Boolean.TRUE.equals(a.getIsSystem())) {
                            return false;
                        }
                    } else if ("shared".equalsIgnoreCase(scope)) {
                        boolean isSys = Boolean.TRUE.equals(a.getIsSystem());
                        boolean isMine = actor != null && actor.getUserId() != null && actor.getUserId().equals(a.getOwnerId());
                        if (isSys || isMine) {
                            return false;
                        }
                    }
                    if (!OwnerNameResolver.matchesOwner(a.getOwnerId(), ownerId)) {
                        return false;
                    }

                    // 3. Keyword search
                    if (keyword != null && !keyword.trim().isEmpty()) {
                        String kw = keyword.trim().toLowerCase();
                        boolean matchName = a.getName() != null && a.getName().toLowerCase().contains(kw);
                        boolean matchDesc = a.getDescription() != null && a.getDescription().toLowerCase().contains(kw);
                        boolean matchCode = a.getCode() != null && a.getCode().toLowerCase().contains(kw);
                        boolean matchTag = a.getTags() != null && a.getTags().stream().anyMatch(t -> t.toLowerCase().contains(kw));
                        String ownerName = a.getOwnerUsername();
                        if (ownerName == null || ownerName.isBlank()) {
                            ownerName = OwnerNameResolver.lookup(names, a.getOwnerId());
                        }
                        String ownerKw = kw.startsWith("@") ? kw.substring(1) : kw;
                        boolean matchOwner = ownerName != null && ownerName.toLowerCase().contains(ownerKw);
                        if (!matchName && !matchDesc && !matchCode && !matchTag && !matchOwner) {
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
        for (Agent agent : pageRecords) {
            if (agent.getOwnerUsername() == null || agent.getOwnerUsername().isBlank()) {
                agent.setOwnerUsername(OwnerNameResolver.lookup(names, agent.getOwnerId()));
            }
        }
        return new PageResult<>(pageRecords, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public Optional<Agent> getById(String id) {
        return getById(id, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public Optional<Agent> getById(String id, CurrentActor actor) {
        Optional<Agent> opt = agentRepository.findById(id);
        if (opt.isPresent() && actor != null && !resourceAuthService.canViewAgent(actor, opt.get())) {
            throw new IllegalStateException("权限不足：无权访问该智能体");
        }
        return opt;
    }

    public Agent create(Agent agent) {
        return create(agent, CurrentActor.get());
    }

    public Agent create(Agent agent, CurrentActor actor) {
        if (actor != null && actor.isViewer()) {
            throw new IllegalStateException("只读观察员无权创建智能体资产");
        }
        if (actor != null && agent.getKnowledgeBaseIds() != null) {
            validateKnowledgeBaseBindings(actor, agent.getKnowledgeBaseIds());
        }
        agent.setId(null);
        if (actor != null) {
            agent.setOwnerId(actor.getUserId());
            agent.setOwnerUsername(actor.getUsername());
            if (!actor.isSuperAdmin()) {
                agent.setIsSystem(false);
            }
        } else {
            agent.setIsSystem(false);
        }
        agent.setToolsConfig(toolConfigSanitizer.sanitize(agent.getToolsConfig()));
        return agentRepository.save(agent);
    }

    public Agent update(String id, Agent agentUpdate) {
        return update(id, agentUpdate, CurrentActor.get());
    }

    public Agent update(String id, Agent agentUpdate, CurrentActor actor) {
        Agent existing = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + id));

        if (!resourceAuthService.canManageAgent(actor, existing)) {
            if (actor != null && Boolean.TRUE.equals(existing.getIsSystem())) {
                throw new IllegalStateException("系统公共预置资产受平台保护，仅超级管理员可直接修改。请点击「复制」创建您的专属智能体！");
            }
            throw new IllegalStateException("权限不足：无法修改其他开发者的个人智能体");
        }

        if (actor != null && agentUpdate.getKnowledgeBaseIds() != null) {
            validateKnowledgeBaseBindings(actor, agentUpdate.getKnowledgeBaseIds());
        }

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
        if (agentUpdate.getToolsConfig() != null) {
            existing.setToolsConfig(toolConfigSanitizer.sanitize(agentUpdate.getToolsConfig()));
        }
        if (agentUpdate.getKnowledgeBaseIds() != null) {
            existing.setKnowledgeBaseIds(new ArrayList<>(agentUpdate.getKnowledgeBaseIds()));
        }
        if (actor != null && actor.isSuperAdmin() && agentUpdate.getIsSystem() != null) {
            existing.setIsSystem(agentUpdate.getIsSystem());
        }

        return agentRepository.save(existing);
    }

    public boolean delete(String id) {
        return delete(id, CurrentActor.get());
    }

    public boolean delete(String id, CurrentActor actor) {
        Agent existing = agentRepository.findById(id).orElse(null);
        if (existing == null) {
            return false;
        }

        if (!resourceAuthService.canManageAgent(actor, existing)) {
            if (actor != null && Boolean.TRUE.equals(existing.getIsSystem())) {
                throw new IllegalStateException("系统公共预置资产受平台保护，仅超级管理员可删除");
            }
            throw new IllegalStateException("权限不足：无法删除其他开发者的个人智能体");
        }

        toolSecretService.clearForDeletedAgent(id);
        resourceGrantRepository.deleteByResourceTypeAndResourceId(ResourceAuthorizationService.TYPE_AGENT, id);
        agentRepository.deleteById(id);
        return true;
    }

    public Agent updateStatus(String id, AgentStatus status) {
        return updateStatus(id, status, CurrentActor.get());
    }

    public Agent updateStatus(String id, AgentStatus status, CurrentActor actor) {
        if (status == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        Agent existing = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + id));

        if (!resourceAuthService.canManageAgent(actor, existing)) {
            if (actor != null && Boolean.TRUE.equals(existing.getIsSystem())) {
                throw new IllegalStateException("系统公共预置资产受平台保护，仅超级管理员可启停，请复制为个人智能体后再调整运行状态");
            }
            throw new IllegalStateException("权限不足：无法启停其他开发者的个人智能体");
        }

        existing.setStatus(status);
        return agentRepository.save(existing);
    }

    public Agent copyAgent(String sourceId) {
        return copyAgent(sourceId, CurrentActor.get());
    }

    public Agent copyAgent(String sourceId, CurrentActor actor) {
        Agent source = agentRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("未找到待复制的智能体: " + sourceId));

        if (!resourceAuthService.canCopyAgent(actor, source)) {
            throw new IllegalStateException("权限不足：无法复制该智能体");
        }

        Agent clone = new Agent();
        clone.setName(source.getName() != null ? source.getName() + " (副本)" : "未命名智能体 (副本)");
        clone.setCode(generateUniqueCopyCode(source.getCode()));
        clone.setAvatar(source.getAvatar());
        clone.setCategory(source.getCategory() != null ? source.getCategory() : "通用智能");
        clone.setDescription(source.getDescription());
        clone.setModelName(source.getModelName());
        clone.setSystemPrompt(source.getSystemPrompt());
        clone.setTemperature(source.getTemperature());
        clone.setTopP(source.getTopP());
        clone.setMaxTokens(source.getMaxTokens());
        clone.setStatus(source.getStatus() != null ? source.getStatus() : AgentStatus.RUNNING);
        if (source.getTags() != null) {
            clone.setTags(new ArrayList<>(source.getTags()));
        }
        // 关键安全修复：脱敏清洗配置，绝不复制明文 API 密钥
        clone.setToolsConfig(toolConfigSanitizer.sanitize(source.getToolsConfig()));

        // 关键安全修复：复制智能体时，仅保留新拥有者有权使用的知识库
        if (source.getKnowledgeBaseIds() != null) {
            List<String> validKbs = new ArrayList<>();
            for (String kbId : source.getKnowledgeBaseIds()) {
                if (kbId == null || kbId.isBlank()) continue;
                knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                    if (resourceAuthService.canUseKnowledgeBase(actor, kb)) {
                        validKbs.add(kbId);
                    }
                });
            }
            clone.setKnowledgeBaseIds(validKbs);
        }
        clone.setId(null);
        clone.setApiKey(null);
        clone.setCallCount(0L);
        clone.setAvgResponseTimeMs(0.0);

        if (actor != null) {
            clone.setOwnerId(actor.getUserId());
            clone.setOwnerUsername(actor.getUsername());
        }
        clone.setIsSystem(false);

        Agent saved = agentRepository.save(clone);

        // 关键安全修复：复制智能体绝不携带工具密钥！避免跨智能体密钥泄露
        // （已彻底移除 toolSecretService.copyForClonedAgent 调用）

        return saved;
    }

    private void validateKnowledgeBaseBindings(CurrentActor actor, List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty() || actor == null || actor.isSuperAdmin()) {
            return;
        }
        for (String kbId : kbIds) {
            if (kbId == null || kbId.isBlank()) continue;
            com.example.agentplatform.model.KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                    .orElseThrow(() -> new IllegalArgumentException("绑定的知识库不存在: " + kbId));
            if (!resourceAuthService.canUseKnowledgeBase(actor, kb)) {
                throw new IllegalStateException("权限不足：无权绑定未获得 USE 授权的知识库 [" + kb.getName() + "]");
            }
        }
    }

    private String generateUniqueCopyCode(String sourceCode) {
        String base = (sourceCode != null && !sourceCode.isBlank()) ? sourceCode.trim() : "agent";
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        String target = base + "_copy";
        if (!agentRepository.existsByCode(target)) {
            return target;
        }
        int idx = 1;
        while (agentRepository.existsByCode(base + "_copy_" + idx)) {
            idx++;
        }
        return base + "_copy_" + idx;
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

        agentRepository.findById(id).ifPresent(agent -> {
            long currentCalls = agent.getCallCount() == null ? 0L : agent.getCallCount();
            long newCalls = currentCalls + 1L;
            agent.setCallCount(newCalls);

            double currentAvg = agent.getAvgResponseTimeMs() == null ? 0.0 : agent.getAvgResponseTimeMs();
            double newAvg = Math.round(((currentAvg * currentCalls + Math.max(0, latencyMs)) / (double) newCalls) * 10.0) / 10.0;
            agent.setAvgResponseTimeMs(newAvg);

            agentRepository.save(agent);
        });
    }

    public void recordExecutedModel(String id, String model) {
        if (id == null || model == null || model.isBlank()) {
            return;
        }
        String executed = model.trim();
        agentRepository.findById(id).ifPresent(agent -> {
            if (executed.equals(agent.getModelName())) {
                return;
            }
            agent.setModelName(executed);
            agentRepository.save(agent);
        });
    }

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats() {
        return getDashboardStats("7days", CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats(String range) {
        return getDashboardStats(range, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats(String range, CurrentActor actor) {
        int days = resolveRangeDays(range);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1L);

        List<Agent> all = agentRepository.findAll();
        List<Agent> accessible = all.stream()
                .filter(a -> actor == null || resourceAuthService.canViewAgent(actor, a))
                .collect(Collectors.toList());
        Set<String> accessibleIds = accessible.stream().map(Agent::getId).collect(Collectors.toSet());

        Map<String, Agent> agentById = accessible.stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(Agent::getId, a -> a, (a, b) -> a));

        List<AgentDailyStat> current = dailyStatRepository.findByStatDateBetween(start, end).stream()
                .filter(row -> accessibleIds.contains(row.getAgentId()))
                .collect(Collectors.toList());
        List<AgentDailyStat> previous = dailyStatRepository.findByStatDateBetween(prevStart, prevEnd).stream()
                .filter(row -> accessibleIds.contains(row.getAgentId()))
                .collect(Collectors.toList());

        DashboardStats stats = new DashboardStats();
        stats.setTotalAgents(accessible.size());
        stats.setRunningAgents(accessible.stream().filter(a -> a.getStatus() == AgentStatus.RUNNING).count());
        stats.setIdleAgents(accessible.stream().filter(a -> a.getStatus() == AgentStatus.IDLE).count());
        stats.setDisabledAgents(accessible.stream().filter(a -> a.getStatus() == AgentStatus.DISABLED).count());

        long totalCalls = accessible.stream().mapToLong(a -> a.getCallCount() == null ? 0 : a.getCallCount()).sum();
        stats.setTotalCalls(totalCalls);

        long latencyCalls = accessible.stream().mapToLong(a -> a.getCallCount() == null ? 0 : a.getCallCount()).sum();
        double latencySum = accessible.stream()
                .filter(a -> a.getCallCount() != null && a.getCallCount() > 0)
                .mapToDouble(a -> (a.getAvgResponseTimeMs() == null ? 0.0 : a.getAvgResponseTimeMs()) * a.getCallCount())
                .sum();
        double avgLatency = latencyCalls == 0 ? 0.0 : latencySum / latencyCalls;
        stats.setAvgResponseTimeMs(Math.round(avgLatency * 10.0) / 10.0);

        long successCount = current.stream().mapToLong(AgentDailyStat::getSuccessCount).sum();
        long successCalls = current.stream().mapToLong(AgentDailyStat::getCallCount).sum();
        double successRate = successCalls == 0 ? 0.0 : Math.round(successCount * 1000.0 / successCalls) / 10.0;
        stats.setSuccessRate(successRate);

        Map<String, Long> categoryCount = accessible.stream()
                .collect(Collectors.groupingBy(a -> a.getCategory() == null ? "其它" : a.getCategory(), Collectors.counting()));
        stats.setCategoryDistribution(categoryCount);

        long promptTokens = current.stream().mapToLong(AgentDailyStat::getPromptTokens).sum();
        long completionTokens = current.stream().mapToLong(AgentDailyStat::getCompletionTokens).sum();
        stats.setPromptTokens(promptTokens);
        stats.setCompletionTokens(completionTokens);
        long totalTokens = promptTokens + completionTokens;
        Map<String, Long> modelTokens = conversationService.tokenUsageByModel(null, accessibleIds, start, end);
        stats.setModelDistribution(modelTokens);
        stats.setEstimatedCostCny(LlmPriceCatalog.estimateCny(modelTokens, promptTokens, completionTokens));

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

        Map<String, long[]> rankAgg = new HashMap<>();
        for (Agent agent : accessible) {
            long calls = agent.getCallCount() == null ? 0 : agent.getCallCount();
            if (calls <= 0) {
                continue;
            }
            rankAgg.put(agent.getId(), new long[]{calls, 0L});
        }
        for (AgentDailyStat row : current) {
            long[] agg = rankAgg.get(row.getAgentId());
            if (agg != null) {
                agg[1] += row.getPromptTokens() + row.getCompletionTokens();
            }
        }
        Map<String, String> latestModels = conversationService.latestModelByAgent(accessibleIds);
        List<DashboardStats.RankingItem> ranking = rankAgg.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(4)
                .map(entry -> {
                    Agent agent = agentById.get(entry.getKey());
                    DashboardStats.RankingItem item = new DashboardStats.RankingItem();
                    item.setAvatar(agent != null && agent.getAvatar() != null ? agent.getAvatar() : "🤖");
                    item.setName(agent != null ? agent.getName() : entry.getKey());
                    item.setModel(resolveRankingModel(entry.getKey(), agent, latestModels));
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
        return getAgentMonitor(agentId, range, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public AgentMonitorStats getAgentMonitor(String agentId, String range, CurrentActor actor) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + agentId));
        if (actor != null && !resourceAuthService.canViewAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：无权查看该智能体的监控数据");
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
        stats.setEstimatedCostCny(LlmPriceCatalog.estimateCny(
                conversationService.tokenUsageByModel(agentId, start, end), promptTokens, completionTokens));

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

    private static String resolveRankingModel(String agentId, Agent agent, Map<String, String> latestModels) {
        String used = latestModels.get(agentId);
        if (used != null && !used.isBlank()) {
            return used;
        }
        if (agent != null && agent.getModelName() != null && !agent.getModelName().isBlank()) {
            return agent.getModelName();
        }
        return "未指定";
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
