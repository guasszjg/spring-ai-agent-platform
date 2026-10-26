package com.shimeta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** 智能体系统提示词的一个历史版本（V19）。 */
@Entity
@Table(name = "agent_prompt_versions")
public class AgentPromptVersion {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(length = 200)
    private String note;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = "apv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
