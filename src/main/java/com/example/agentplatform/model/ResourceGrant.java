package com.example.agentplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "resource_grants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_resource_grant",
                        columnNames = {"resource_type", "resource_id", "grantee_user_id"}
                )
        }
)
public class ResourceGrant {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType; // AGENT, KNOWLEDGE_BASE, TEMPLATE

    @Column(name = "resource_id", length = 64, nullable = false)
    private String resourceId;

    @Column(name = "grantee_user_id", length = 64, nullable = false)
    private String granteeUserId;

    @Column(name = "grantee_username", length = 64)
    private String granteeUsername;

    @Column(length = 32, nullable = false)
    private String level; // Agent: VIEW, RUN; KnowledgeBase: VIEW, USE; Template: USE

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ResourceGrant() {
    }

    public ResourceGrant(String resourceType, String resourceId, String granteeUserId, String granteeUsername, String level, String grantedBy) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.granteeUserId = granteeUserId;
        this.granteeUsername = granteeUsername;
        this.level = level;
        this.grantedBy = grantedBy;
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = "grant-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getGranteeUserId() {
        return granteeUserId;
    }

    public void setGranteeUserId(String granteeUserId) {
        this.granteeUserId = granteeUserId;
    }

    public String getGranteeUsername() {
        return granteeUsername;
    }

    public void setGranteeUsername(String granteeUsername) {
        this.granteeUsername = granteeUsername;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
