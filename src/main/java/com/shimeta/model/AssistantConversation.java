package com.shimeta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** 平台 AI 助手会话：只属于发起它的用户。 */
@Entity
@Table(name = "assistant_conversations")
public class AssistantConversation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(length = 200)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 软删除时间：删除后对用户不可见，消息内容已清空，仅保留 token 用量供统计 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 早期对话滚动记忆摘要（超出滑窗的较早轮次在后台异步浓缩保存） */
    @Column(name = "memory_summary", columnDefinition = "TEXT")
    private String memorySummary;

    /** 已被浓缩进 memorySummary 的历史消息条数（按时间正序前 N 条） */
    @Column(name = "summary_msg_count")
    private Integer summaryMsgCount = 0;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "asc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getMemorySummary() { return memorySummary; }
    public void setMemorySummary(String memorySummary) { this.memorySummary = memorySummary; }
    public Integer getSummaryMsgCount() { return summaryMsgCount == null ? 0 : summaryMsgCount; }
    public void setSummaryMsgCount(Integer summaryMsgCount) { this.summaryMsgCount = summaryMsgCount == null ? 0 : Math.max(0, summaryMsgCount); }
}
