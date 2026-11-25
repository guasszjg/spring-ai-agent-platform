package com.shimeta.model;

import com.shimeta.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 智能体分享链接：访客打开 /chat/{code} 后免登录对话。各项上限为空表示不限 */
@Entity
@Table(name = "agent_share_links")
public class AgentShareLink {

    public static final String MODE_ACCESS_CODE = "ACCESS_CODE";
    public static final String MODE_PUBLIC = "PUBLIC";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;
    @Column(nullable = false, length = 64)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "access_mode", nullable = false, length = 16)
    private String accessMode;
    @Column(name = "access_code_hash", length = 100)
    private String accessCodeHash;
    /** 访问码的 AES-GCM 密文（SecretCrypto），供创建人查看；访客校验只用哈希 */
    @Column(name = "access_code_cipher", length = 255)
    private String accessCodeCipher;
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    @Column(name = "max_messages")
    private Integer maxMessages;
    @Column(name = "max_tokens")
    private Long maxTokens;
    @Column(name = "max_visitors")
    private Integer maxVisitors;
    @Column(name = "visitor_rpm")
    private Integer visitorRpm;
    @Column(length = 100)
    private String title;
    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "example_questions", columnDefinition = "TEXT")
    private List<String> exampleQuestions = new ArrayList<>();
    @Column(name = "show_record_notice")
    private Boolean showRecordNotice = true;
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;
    @Column(name = "api_key_id", length = 64)
    private String apiKeyId;
    @Column(name = "message_count", nullable = false)
    private long messageCount;
    @Column(name = "token_count", nullable = false)
    private long tokenCount;
    @Column(name = "visitor_count", nullable = false)
    private int visitorCount;
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
    @Column(name = "created_by", length = 64)
    private String createdBy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean messagesExhausted() {
        return maxMessages != null && messageCount >= maxMessages;
    }

    public boolean tokensExhausted() {
        return maxTokens != null && tokenCount >= maxTokens;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAccessMode() { return accessMode; }
    public void setAccessMode(String accessMode) { this.accessMode = accessMode; }
    public String getAccessCodeHash() { return accessCodeHash; }
    public void setAccessCodeHash(String accessCodeHash) { this.accessCodeHash = accessCodeHash; }
    public String getAccessCodeCipher() { return accessCodeCipher; }
    public void setAccessCodeCipher(String accessCodeCipher) { this.accessCodeCipher = accessCodeCipher; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Integer getMaxMessages() { return maxMessages; }
    public void setMaxMessages(Integer maxMessages) { this.maxMessages = maxMessages; }
    public Long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Long maxTokens) { this.maxTokens = maxTokens; }
    public Integer getMaxVisitors() { return maxVisitors; }
    public void setMaxVisitors(Integer maxVisitors) { this.maxVisitors = maxVisitors; }
    public Integer getVisitorRpm() { return visitorRpm; }
    public void setVisitorRpm(Integer visitorRpm) { this.visitorRpm = visitorRpm; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public List<String> getExampleQuestions() { return exampleQuestions; }
    public void setExampleQuestions(List<String> exampleQuestions) { this.exampleQuestions = exampleQuestions; }
    public Boolean getShowRecordNotice() { return showRecordNotice; }
    public void setShowRecordNotice(Boolean showRecordNotice) { this.showRecordNotice = showRecordNotice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(String apiKeyId) { this.apiKeyId = apiKeyId; }
    public long getMessageCount() { return messageCount; }
    public void setMessageCount(long messageCount) { this.messageCount = messageCount; }
    public long getTokenCount() { return tokenCount; }
    public void setTokenCount(long tokenCount) { this.tokenCount = tokenCount; }
    public int getVisitorCount() { return visitorCount; }
    public void setVisitorCount(int visitorCount) { this.visitorCount = visitorCount; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
