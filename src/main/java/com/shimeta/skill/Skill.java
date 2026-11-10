package com.shimeta.skill;

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
import java.util.UUID;

@Entity
@Table(name = "skills")
public class Skill {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64, unique = true)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "required_tools", columnDefinition = "TEXT")
    private List<String> requiredTools = new ArrayList<>();

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "owner_username", length = 64)
    private String ownerUsername;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "load_count", nullable = false)
    private Long loadCount = 0L;

    @Column(name = "last_loaded_at")
    private LocalDateTime lastLoadedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "skill-" + UUID.randomUUID().toString().substring(0, 8);
        }
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getRequiredTools() { return requiredTools; }
    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools != null ? requiredTools : new ArrayList<>();
    }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getLoadCount() { return loadCount; }
    public void setLoadCount(Long loadCount) { this.loadCount = loadCount; }
    public LocalDateTime getLastLoadedAt() { return lastLoadedAt; }
    public void setLastLoadedAt(LocalDateTime lastLoadedAt) { this.lastLoadedAt = lastLoadedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
