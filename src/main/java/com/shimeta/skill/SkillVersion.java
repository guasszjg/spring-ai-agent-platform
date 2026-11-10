package com.shimeta.skill;

import com.shimeta.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 技能的一个历史版本：每次修改描述、步骤或需要的工具时保存一份 */
@Entity
@Table(name = "skill_versions")
public class SkillVersion {

    public static final String SOURCE_CREATE = "CREATE";
    public static final String SOURCE_EDIT = "EDIT";
    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_RESTORE = "RESTORE";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "required_tools", columnDefinition = "TEXT")
    private List<String> requiredTools = new ArrayList<>();

    @Column(length = 16)
    private String source;

    @Column(length = 200)
    private String note;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "skv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static SkillVersion snapshot(Skill skill, String source, String note, String createdBy) {
        SkillVersion v = new SkillVersion();
        v.skillId = skill.getId();
        v.version = skill.getVersion();
        v.name = skill.getName();
        v.description = skill.getDescription();
        v.content = skill.getContent();
        v.requiredTools = new ArrayList<>(skill.getRequiredTools() == null ? List.of() : skill.getRequiredTools());
        v.source = source;
        v.note = note == null || note.isBlank() ? null : (note.length() > 200 ? note.substring(0, 200) : note.trim());
        v.createdBy = createdBy;
        return v;
    }

    public String getId() { return id; }
    public String getSkillId() { return skillId; }
    public Integer getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getContent() { return content; }
    public List<String> getRequiredTools() { return requiredTools; }
    public String getSource() { return source; }
    public String getNote() { return note; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
