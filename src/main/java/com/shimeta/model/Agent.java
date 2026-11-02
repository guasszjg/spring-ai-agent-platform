package com.shimeta.model;

import com.shimeta.config.StringListJsonConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "agents")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Agent {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(unique = true, length = 100)
    private String code;
    @Column(length = 64)
    private String avatar;
    @Column(length = 64)
    private String category;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(length = 100)
    private String modelName;
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    /** 上下文记忆轮数：每次对话带上最近 N 轮问答，0 = 关闭记忆 */
    @Column(name = "memory_turns")
    private Integer memoryTurns = DEFAULT_MEMORY_TURNS;
    /** 思考模式：AUTO 跟随模型默认 / ON 开启 / OFF 关闭 */
    @Column(name = "thinking_mode", length = 8)
    private String thinkingMode = THINKING_AUTO;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tags", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "tag", length = 64)
    private List<String> tags = new ArrayList<>();
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AgentStatus status;
    private Long callCount;
    private Double avgResponseTimeMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Column(columnDefinition = "TEXT")
    private String toolsConfig;
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "knowledge_base_ids", columnDefinition = "TEXT")
    private List<String> knowledgeBaseIds = new ArrayList<>();
    /** 已废弃：早期按智能体生成的明文 Key。V23 起清空且不再生成，开放调用一律使用「开放凭证」（只存哈希）。保留字段仅为兼容旧库结构 */
    @Column(unique = true, length = 128)
    private String apiKey;
    @Column(name = "owner_id", length = 64)
    private String ownerId;
    @Column(name = "owner_username", length = 64)
    private String ownerUsername;
    @Column(name = "is_system")
    private Boolean isSystem = false;

    /** 更新请求中可选的提示词版本说明（只写入版本记录，不入库到智能体表，也不出现在响应中） */
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String promptChangeNote;

    /** 更新请求中可选的提示词变更来源：PUBLISH 调试台发布 / EDIT 编辑 / ROLLBACK 回滚 */
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String promptChangeSource;

    public String getPromptChangeNote() {
        return promptChangeNote;
    }

    public void setPromptChangeNote(String promptChangeNote) {
        this.promptChangeNote = promptChangeNote;
    }

    public String getPromptChangeSource() {
        return promptChangeSource;
    }

    public void setPromptChangeSource(String promptChangeSource) {
        this.promptChangeSource = promptChangeSource;
    }

    public Agent() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = AgentStatus.RUNNING;
        this.callCount = 0L;
        this.avgResponseTimeMs = 0.0;
        this.temperature = 0.7;
        this.topP = 0.9;
        this.maxTokens = 2048;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public static final int DEFAULT_MEMORY_TURNS = 0;
    public static final String THINKING_AUTO = "AUTO";
    public static final String THINKING_ON = "ON";
    public static final String THINKING_OFF = "OFF";

    public String getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(String thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    /** 规范化思考模式：大小写不敏感，空值视为 AUTO，其他取值拒绝 */
    public static String normalizeThinkingMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return THINKING_AUTO;
        }
        String upper = mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!THINKING_AUTO.equals(upper) && !THINKING_ON.equals(upper) && !THINKING_OFF.equals(upper)) {
            throw new IllegalArgumentException("思考模式只能是 AUTO（跟随模型默认）、ON（开启）或 OFF（关闭）");
        }
        return upper;
    }

    /** 发给模型的思考参数：null 表示不传，由模型按默认行为决定 */
    public Boolean resolveThinking() {
        String mode = thinkingMode == null ? THINKING_AUTO : thinkingMode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (mode) {
            case THINKING_ON -> Boolean.TRUE;
            case THINKING_OFF -> Boolean.FALSE;
            default -> null;
        };
    }
    public static final int MAX_MEMORY_TURNS = 10;

    public Integer getMemoryTurns() {
        return memoryTurns;
    }

    public void setMemoryTurns(Integer memoryTurns) {
        this.memoryTurns = memoryTurns;
    }

    /** 实际生效的记忆轮数：未设置时用默认值，超出范围时截到 0～{@value #MAX_MEMORY_TURNS} */
    public int resolveMemoryTurns() {
        if (memoryTurns == null) {
            return DEFAULT_MEMORY_TURNS;
        }
        return Math.max(0, Math.min(MAX_MEMORY_TURNS, memoryTurns));
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public Long getCallCount() {
        return callCount;
    }

    public void setCallCount(Long callCount) {
        this.callCount = callCount;
    }

    public Double getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    public void setAvgResponseTimeMs(Double avgResponseTimeMs) {
        this.avgResponseTimeMs = avgResponseTimeMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getToolsConfig() {
        return toolsConfig;
    }

    public void setToolsConfig(String toolsConfig) {
        this.toolsConfig = toolsConfig;
    }

    public List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds != null ? knowledgeBaseIds : new ArrayList<>();
    }

    @JsonIgnore
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = AgentStatus.RUNNING;
        }
        if (callCount == null) {
            callCount = 0L;
        }
        if (avgResponseTimeMs == null) {
            avgResponseTimeMs = 0.0;
        }
        if (avatar == null || avatar.isBlank()) {
            avatar = "🤖";
        }
        if (tags == null) {
            tags = new ArrayList<>();
        }
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public Boolean getIsSystem() {
        return isSystem;
    }

    public void setIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
