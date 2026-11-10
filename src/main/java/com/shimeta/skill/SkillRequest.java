package com.shimeta.skill;

import java.util.List;

/** 新建或修改技能；修改时为空的字段保持不变 */
public class SkillRequest {
    private String name;
    private String description;
    private String content;
    private List<String> requiredTools;
    private Boolean isSystem;
    private Boolean enabled;
    /** 本次修改的说明，写进版本记录 */
    private String changeNote;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getRequiredTools() { return requiredTools; }
    public void setRequiredTools(List<String> requiredTools) { this.requiredTools = requiredTools; }
    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}
