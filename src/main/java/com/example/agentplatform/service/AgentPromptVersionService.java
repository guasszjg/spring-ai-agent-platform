package com.example.agentplatform.service;

import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentPromptVersion;
import com.example.agentplatform.repository.AgentPromptVersionRepository;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 智能体系统提示词版本管理。
 *
 * <p>系统提示词每次发生变化都记录一个版本（由 {@link AgentService} 在创建、复制、更新时调用）；版本只追加不修改，
 * 回滚就是把旧版本的内容再发布一次，生成一个新版本。启用版本管理之前已存在的智能体没有历史，第一次修改时先把
 * 修改前的提示词补记为"初始版本"。每个智能体最多保留最近 {@value #MAX_VERSIONS} 个版本。
 */
@Service
@Transactional
public class AgentPromptVersionService {

    public static final String SOURCE_INITIAL = "INITIAL";
    public static final String SOURCE_CREATE = "CREATE";
    public static final String SOURCE_COPY = "COPY";
    public static final String SOURCE_PUBLISH = "PUBLISH";
    public static final String SOURCE_EDIT = "EDIT";
    public static final String SOURCE_ROLLBACK = "ROLLBACK";
    public static final String SOURCE_ASSISTANT = "ASSISTANT";

    /** 前端（含开放 API）可以声明的来源；其余来源只能由服务端设置 */
    private static final Set<String> CLIENT_SOURCES = Set.of(SOURCE_PUBLISH, SOURCE_EDIT, SOURCE_ROLLBACK);
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            SOURCE_INITIAL, "初始版本", SOURCE_CREATE, "创建", SOURCE_COPY, "复制", SOURCE_PUBLISH, "调试台发布",
            SOURCE_EDIT, "编辑", SOURCE_ROLLBACK, "回滚", SOURCE_ASSISTANT, "AI 助手");

    static final int MAX_VERSIONS = 100;
    static final int NOTE_MAX = 200;
    private static final int PREVIEW_MAX = 80;

    private final AgentPromptVersionRepository versionRepository;
    private final AgentRepository agentRepository;
    private final ResourceAuthorizationService authorizationService;

    public AgentPromptVersionService(AgentPromptVersionRepository versionRepository,
                                     AgentRepository agentRepository,
                                     ResourceAuthorizationService authorizationService) {
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
        this.authorizationService = authorizationService;
    }

    /** 前端声明的来源只接受发布 / 编辑 / 回滚，其他值一律按"编辑"记录。 */
    public static String clientSource(String source) {
        return source != null && CLIENT_SOURCES.contains(source) ? source : SOURCE_EDIT;
    }

    // ==================== 记录 ====================

    /** 新建或复制的智能体：记录第 1 个版本（提示词为空时不记录）。 */
    public void recordCreated(Agent agent, String source, String note, CurrentActor actor) {
        if (agent == null || agent.getId() == null || isBlank(agent.getSystemPrompt())) {
            return;
        }
        save(agent.getId(), 1, agent.getSystemPrompt(), source, note, actor);
    }

    /** 提示词发生变化时记录新版本；内容未变化时不记录。 */
    public void recordChange(String agentId, String before, String after, String source, String note, CurrentActor actor) {
        if (agentId == null || after == null || Objects.equals(normalize(before), normalize(after))) {
            return;
        }
        AgentPromptVersion latest = versionRepository.findFirstByAgentIdOrderByVersionNoDesc(agentId).orElse(null);
        int next;
        if (latest == null) {
            // 启用版本管理之前的智能体：先补记修改前的提示词，便于回滚
            if (!isBlank(before)) {
                save(agentId, 1, before, SOURCE_INITIAL, "启用版本管理前的提示词", null);
                next = 2;
            } else {
                next = 1;
            }
        } else {
            next = latest.getVersionNo() + 1;
        }
        save(agentId, next, after, source, note, actor);
        prune(agentId);
    }

    public void deleteForAgent(String agentId) {
        versionRepository.deleteByAgentId(agentId);
    }

    // ==================== 查询 ====================

    /** 版本列表（不含全文）。current 表示该版本与当前线上提示词一致。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String agentId, CurrentActor actor) {
        Agent agent = loadViewable(agentId, actor);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean currentMarked = false;
        for (AgentPromptVersion v : versionRepository.findByAgentIdOrderByVersionNoDesc(agentId)) {
            boolean current = !currentMarked && Objects.equals(normalize(v.getSystemPrompt()), normalize(agent.getSystemPrompt()));
            currentMarked |= current;
            Map<String, Object> m = summary(v);
            m.put("current", current);
            items.add(m);
        }
        return items;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String agentId, String versionId, CurrentActor actor) {
        loadViewable(agentId, actor);
        AgentPromptVersion v = versionRepository.findByIdAndAgentId(versionId, agentId)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在"));
        Map<String, Object> m = summary(v);
        m.put("systemPrompt", v.getSystemPrompt() == null ? "" : v.getSystemPrompt());
        return m;
    }

    // ==================== 内部 ====================

    private Agent loadViewable(String agentId, CurrentActor actor) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在"));
        if (actor == null || !authorizationService.canViewAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：无权查看该智能体");
        }
        return agent;
    }

    private void save(String agentId, int versionNo, String prompt, String source, String note, CurrentActor actor) {
        AgentPromptVersion v = new AgentPromptVersion();
        v.setAgentId(agentId);
        v.setVersionNo(versionNo);
        v.setSystemPrompt(prompt);
        v.setSource(source == null ? SOURCE_EDIT : source);
        v.setNote(trimNote(note));
        if (actor != null) {
            v.setCreatedBy(actor.getUserId());
            v.setCreatedByName(actor.getNickname() != null && !actor.getNickname().isBlank() ? actor.getNickname() : actor.getUsername());
        }
        versionRepository.save(v);
    }

    private void prune(String agentId) {
        List<AgentPromptVersion> all = versionRepository.findByAgentIdOrderByVersionNoDesc(agentId);
        if (all.size() > MAX_VERSIONS) {
            versionRepository.deleteAll(all.subList(MAX_VERSIONS, all.size()));
        }
    }

    private static Map<String, Object> summary(AgentPromptVersion v) {
        String prompt = v.getSystemPrompt() == null ? "" : v.getSystemPrompt();
        String flat = prompt.replaceAll("\\s+", " ").trim();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("versionNo", v.getVersionNo());
        m.put("source", v.getSource());
        m.put("sourceLabel", SOURCE_LABELS.getOrDefault(v.getSource(), v.getSource()));
        m.put("note", v.getNote());
        m.put("createdBy", v.getCreatedByName());
        m.put("createdAt", v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
        m.put("length", prompt.length());
        m.put("preview", flat.length() > PREVIEW_MAX ? flat.substring(0, PREVIEW_MAX) + "…" : flat);
        return m;
    }

    static String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > NOTE_MAX ? trimmed.substring(0, NOTE_MAX) : trimmed;
    }

    /** 只有行尾空白或换行符差异时不算修改 */
    private static String normalize(String prompt) {
        return prompt == null ? "" : prompt.replace("\r\n", "\n").stripTrailing();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
