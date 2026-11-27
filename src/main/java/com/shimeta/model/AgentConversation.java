package com.shimeta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_conversations")
public class AgentConversation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(length = 240)
    private String title;

    @Column(length = 160)
    private String account;

    private int messageCount;

    @Column(length = 32)
    private String userFeedback;

    @Column(length = 32)
    private String adminFeedback;

    @Column(length = 120)
    private String lastModel;

    private long totalTokens;

    /** 早期对话滚动记忆摘要（开启智能体「记忆压缩」后异步维护，仅压缩滑窗之前的轮次） */
    @Column(name = "memory_summary", columnDefinition = "TEXT")
    private String memorySummary;

    /** 已被浓缩进 memorySummary 的历史消息条数（按时间正序前 N 条） */
    @Column(name = "summary_msg_count")
    private Integer summaryMsgCount = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentConversation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getUserFeedback() {
        return userFeedback;
    }

    public void setUserFeedback(String userFeedback) {
        this.userFeedback = userFeedback;
    }

    public String getAdminFeedback() {
        return adminFeedback;
    }

    public void setAdminFeedback(String adminFeedback) {
        this.adminFeedback = adminFeedback;
    }

    public String getLastModel() {
        return lastModel;
    }

    public void setLastModel(String lastModel) {
        this.lastModel = lastModel;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getMemorySummary() {
        return memorySummary;
    }

    public void setMemorySummary(String memorySummary) {
        this.memorySummary = memorySummary;
    }

    public Integer getSummaryMsgCount() {
        return summaryMsgCount == null ? 0 : summaryMsgCount;
    }

    public void setSummaryMsgCount(Integer summaryMsgCount) {
        this.summaryMsgCount = summaryMsgCount == null ? 0 : Math.max(0, summaryMsgCount);
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

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "conv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (account == null || account.isBlank()) {
            account = "debug";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
